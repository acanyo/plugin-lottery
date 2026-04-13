package com.xhhao.lottery.service.impl;

import com.xhhao.lottery.entity.LotteryActivity;
import com.xhhao.lottery.entity.LotteryParticipant;
import com.xhhao.lottery.service.InstantLotteryStockService;
import com.xhhao.lottery.service.RedisConfigService;
import io.lettuce.core.ScriptOutputType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static run.halo.app.extension.index.query.Queries.equal;

@Slf4j
@Service
public class RedisInstantLotteryStockService implements InstantLotteryStockService {

    private static final String STOCK_KEY_PREFIX = "plugin:lottery:instant:stock";
    private static final Duration REDIS_KEY_RETENTION = Duration.ofDays(7);
    private static final Duration REDIS_KEY_FALLBACK_TTL = Duration.ofDays(30);
    private static final String RESERVE_SCRIPT = """
        local rand = tonumber(ARGV[1])
        local total = 0
        local available = {}

        for i = 1, #KEYS do
            local stock = tonumber(redis.call('GET', KEYS[i]) or '0')
            local probability = tonumber(ARGV[i + 1]) or 0
            if stock > 0 and probability > 0 then
                total = total + probability
                table.insert(available, i)
            end
        end

        if total <= 0 or rand >= total then
            return 0
        end

        local cumulative = 0
        for _, index in ipairs(available) do
            cumulative = cumulative + (tonumber(ARGV[index + 1]) or 0)
            if rand < cumulative then
                local current = tonumber(redis.call('GET', KEYS[index]) or '0')
                if current <= 0 then
                    return 0
                end
                redis.call('DECR', KEYS[index])
                return index
            end
        end

        return 0
        """;

    private final ReactiveExtensionClient client;
    private final RedisConfigService redisConfigService;

    public RedisInstantLotteryStockService(
        ReactiveExtensionClient client,
        RedisConfigService redisConfigService
    ) {
        this.client = client;
        this.redisConfigService = redisConfigService;
    }

    @Override
    public Mono<Boolean> isAvailable() {
        return redisConfigService.getRedisConnection()
            .map(connection -> true)
            .defaultIfEmpty(false);
    }

    @Override
    public Mono<Optional<LotteryActivity.Prize>> reservePrize(LotteryActivity activity) {
        var prizes = Optional.ofNullable(activity.getSpec())
            .map(LotteryActivity.LotteryActivitySpec::getPrizes)
            .orElse(List.of());

        if (prizes.isEmpty()) {
            return Mono.just(Optional.empty());
        }

        return redisConfigService.getRedisConnection()
            .switchIfEmpty(Mono.error(new IllegalStateException(
                "即时开奖依赖 Redis 原子扣库存，当前未检测到 Redis 配置，请改用定时开奖。"
            )))
            .flatMap(connection -> ensureStockKeys(connection, activity, prizes)
                .then(executeReserve(connection, activity, prizes)))
            .onErrorMap(throwable -> {
                if (throwable instanceof IllegalStateException) {
                    return throwable;
                }
                log.error("Failed to reserve instant lottery stock for activity {}",
                    activity.getMetadata().getName(), throwable);
                return new IllegalStateException("即时开奖库存服务不可用，请检查 Redis 配置。", throwable);
            });
    }

    @Override
    public Mono<Void> releasePrize(LotteryActivity activity, String prizeName) {
        if (prizeName == null || prizeName.isBlank()) {
            return Mono.empty();
        }

        return redisConfigService.getRedisConnection()
            .flatMap(connection -> {
                var key = stockKey(activity.getMetadata().getName(), prizeName);
                var ttlSeconds = redisTtlSeconds(activity);
                return Mono.fromFuture(connection.async()
                        .incr(key)
                        .toCompletableFuture())
                    .then(Mono.fromFuture(connection.async().expire(key, ttlSeconds).toCompletableFuture()))
                    .then();
            })
            .onErrorResume(throwable -> {
                log.warn("Failed to release instant lottery stock for activity {} prize {}",
                    activity.getMetadata().getName(), prizeName, throwable);
                return Mono.empty();
            });
    }

    private Mono<Void> ensureStockKeys(
        io.lettuce.core.api.StatefulRedisConnection<String, String> connection,
        LotteryActivity activity,
        List<LotteryActivity.Prize> prizes
    ) {
        var activityName = activity.getMetadata().getName();
        var ttlSeconds = redisTtlSeconds(activity);

        return getWinnerCounts(activityName)
            .flatMapMany(winnerCounts -> Flux.fromIterable(prizes)
                .concatMap(prize -> {
                    var key = stockKey(activityName, prize.getName());
                    return Mono.fromFuture(connection.async()
                            .setnx(key, Integer.toString(initialRemaining(prize, winnerCounts)))
                            .toCompletableFuture())
                        .then(Mono.fromFuture(connection.async().expire(key, ttlSeconds).toCompletableFuture()))
                        .then();
                }))
            .then();
    }

    private Mono<Optional<LotteryActivity.Prize>> executeReserve(
        io.lettuce.core.api.StatefulRedisConnection<String, String> connection,
        LotteryActivity activity,
        List<LotteryActivity.Prize> prizes
    ) {
        var activityName = activity.getMetadata().getName();
        List<String> keys = prizes.stream()
            .map(prize -> stockKey(activityName, prize.getName()))
            .toList();

        var args = new ArrayList<String>(prizes.size() + 1);
        args.add(Integer.toString(ThreadLocalRandom.current().nextInt(100)));
        prizes.forEach(prize -> args.add(Integer.toString(
            Objects.requireNonNullElse(prize.getProbability(), 0)
        )));

        return Mono.fromFuture(connection.async()
                .eval(
                    RESERVE_SCRIPT,
                    ScriptOutputType.INTEGER,
                    keys.toArray(String[]::new),
                    args.toArray(String[]::new)
                )
                .toCompletableFuture())
            .cast(Number.class)
            .defaultIfEmpty(0L)
            .map(index -> {
                int prizeIndex = index.intValue();
                if (prizeIndex <= 0 || prizeIndex > prizes.size()) {
                    return Optional.empty();
                }
                return Optional.of(prizes.get(prizeIndex - 1));
            });
    }

    private Mono<Map<String, Long>> getWinnerCounts(String activityName) {
        return client.listAll(
                LotteryParticipant.class,
                ListOptions.builder().fieldQuery(equal("spec.activityName", activityName)).build(),
                null
            )
            .filter(participant -> Boolean.TRUE.equals(participant.getSpec().getIsWinner()))
            .map(participant -> participant.getSpec().getPrizeName())
            .filter(Objects::nonNull)
            .collect(Collectors.groupingBy(prizeName -> prizeName, Collectors.counting()));
    }

    private int initialRemaining(LotteryActivity.Prize prize, Map<String, Long> winnerCounts) {
        int quantity = Objects.requireNonNullElse(prize.getQuantity(), 0);
        long consumed = winnerCounts.getOrDefault(prize.getName(), 0L);
        return Math.max(0, quantity - Math.toIntExact(consumed));
    }

    private String stockKey(String activityName, String prizeName) {
        var encodedPrizeName = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(Objects.requireNonNullElse(prizeName, "")
                .getBytes(StandardCharsets.UTF_8));
        return STOCK_KEY_PREFIX + ":" + activityName + ":" + encodedPrizeName;
    }

    private long redisTtlSeconds(LotteryActivity activity) {
        var now = Instant.now();
        var spec = activity.getSpec();
        var lifecycleEnd = Stream.of(spec.getDrawTime(), spec.getEndTime(), spec.getStartTime())
            .filter(Objects::nonNull)
            .max(Comparator.naturalOrder())
            .orElse(now.plus(REDIS_KEY_FALLBACK_TTL));
        var expireAt = lifecycleEnd.plus(REDIS_KEY_RETENTION);
        return Math.max(3600L, Duration.between(now, expireAt).getSeconds());
    }
}
