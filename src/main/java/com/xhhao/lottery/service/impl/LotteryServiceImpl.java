package com.xhhao.lottery.service.impl;

import com.xhhao.lottery.entity.LotteryActivity;
import com.xhhao.lottery.entity.LotteryActivity.LotteryActivitySpec;
import com.xhhao.lottery.entity.LotteryActivity.LotteryActivityStatus;
import com.xhhao.lottery.entity.LotteryActivity.LotteryType;
import com.xhhao.lottery.entity.LotteryActivity.ParticipationType;
import com.xhhao.lottery.entity.LotteryActivity.Prize;
import com.xhhao.lottery.entity.LotteryActivity.State;
import com.xhhao.lottery.entity.LotteryActivity.Winner;
import com.xhhao.lottery.entity.LotteryParticipant;
import com.xhhao.lottery.entity.LotteryParticipant.LotteryParticipantSpec;
import com.xhhao.lottery.query.LotteryActivityQuery;
import com.xhhao.lottery.service.LotteryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static run.halo.app.extension.index.query.Queries.equal;

@Service
@RequiredArgsConstructor
public class LotteryServiceImpl implements LotteryService {

    private final ReactiveExtensionClient client;

    private static final String TOKEN_SALT = "lottery_plugin_salt_2024";
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[\\w.-]+@[\\w.-]+\\.[a-zA-Z]{2,}$");

    // ==================== 公开接口实现 ====================

    @Override
    public Mono<LotteryActivity> getActivity(String activityName) {
        return client.get(LotteryActivity.class, activityName)
            .flatMap(this::checkAndUpdateState)
            .flatMap(this::checkAndAutoDraw);
    }

    @Override
    public Mono<ListResult<LotteryActivity>> listActivities(LotteryActivityQuery query) {
        return client.listBy(LotteryActivity.class, query.toListOptions(),
                PageRequestImpl.of(query.getPage(), query.getSize(), query.getSort()))
            .flatMap(result -> Flux.fromIterable(result.getItems())
                .flatMap(this::checkAndUpdateState)
                .collectList()
                .map(items -> new ListResult<>(result.getPage(), result.getSize(), result.getTotal(), items)));
    }

    @Override
    public Mono<LotteryActivity> draw(String activityName) {
        return client.get(LotteryActivity.class, activityName)
            .flatMap(activity -> switch (getStatus(activity).getState()) {
                case DRAWN -> Mono.error(new IllegalStateException("活动已开奖"));
                case RUNNING -> executeDraw(activity);
                case null, default -> Mono.error(new IllegalStateException("活动未在进行中"));
            });
    }

    @Override
    public Mono<LotteryParticipant> participateAnonymous(String activityName, String email,
                                                          String displayName, String ipAddress) {
        return validateEmail(email)
            .then(client.get(LotteryActivity.class, activityName))
            .switchIfEmpty(Mono.error(new IllegalArgumentException("活动不存在")))
            .flatMap(activity -> validateParticipation(activity)
                .then(checkDuplicate(activityName, email))
                .then(doParticipate(activity, email, displayName, ipAddress)));
    }

    @Override
    public Mono<LotteryParticipant> findByToken(String token) {
        return Optional.ofNullable(token)
            .filter(t -> !t.isBlank())
            .map(t -> client.listAll(LotteryParticipant.class,
                    ListOptions.builder().fieldQuery(equal("spec.token", t)).build(), null)
                .next())
            .orElse(Mono.empty());
    }

    @Override
    public Mono<LotteryParticipant> findByActivityAndEmail(String activityName, String email) {
        return (activityName == null || email == null)
            ? Mono.empty()
            : findByToken(generateToken(activityName, email));
    }

    @Override
    public Mono<Winner> getWinnerByToken(String activityName, String token) {
        return findByToken(token).flatMap(participant -> {
            var spec = participant.getSpec();
            // 即时开奖：直接从参与记录获取
            if (Boolean.TRUE.equals(spec.getIsWinner())) {
                return Mono.just(createWinner(
                    Objects.requireNonNullElse(spec.getUsername(), spec.getEmail()),
                    spec.getPrizeName(),
                    spec.getWinTime()
                ));
            }
            // 定时开奖：从活动 winners 列表查找
            return findWinnerFromActivity(activityName, spec);
        });
    }

    @Override
    public String generateToken(String activityName, String identifier) {
        try {
            var raw = activityName + ":" + identifier + ":" + TOKEN_SALT;
            var hash = MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("生成 token 失败", e);
        }
    }

    // ==================== 状态管理 ====================

    private Mono<LotteryActivity> checkAndUpdateState(LotteryActivity activity) {
        var status = getStatus(activity);
        if (status.getState() == State.DRAWN) {
            return Mono.just(activity);
        }

        var newState = calculateState(activity.getSpec());
        if (status.getState() != newState) {
            status.setState(newState);
            return client.update(activity);
        }
        return Mono.just(activity);
    }

    private State calculateState(LotteryActivitySpec spec) {
        var now = Instant.now();
        var startTime = Objects.requireNonNullElse(spec.getStartTime(), Instant.EPOCH);

        if (now.isBefore(startTime)) return State.PENDING;
        if (spec.getEndTime() != null && now.isAfter(spec.getEndTime())) return State.ENDED;
        return State.RUNNING;
    }

    private Mono<LotteryActivity> checkAndAutoDraw(LotteryActivity activity) {
        var status = getStatus(activity);
        if (status.getState() == State.DRAWN) {
            return Mono.just(activity);
        }

        var spec = activity.getSpec();
        // drawTime 优先，其次 endTime，都为 null 则不自动开奖
        var drawTime = spec.getDrawTime() != null ? spec.getDrawTime() : spec.getEndTime();

        if (drawTime != null && Instant.now().isAfter(drawTime)
            && (status.getState() == State.RUNNING || status.getState() == State.ENDED)) {
            return executeDraw(activity);
        }
        return Mono.just(activity);
    }

    // ==================== 抽奖核心逻辑 ====================

    /**
     * 执行定时开奖
     */
    private Mono<LotteryActivity> executeDraw(LotteryActivity activity) {
        var prizes = activity.getSpec().getPrizes();
        if (prizes == null || prizes.isEmpty()) {
            return Mono.error(new IllegalStateException("未设置奖品"));
        }

        return getParticipants(activity.getMetadata().getName())
            .collectList()
            .flatMap(participants -> {
                if (participants.isEmpty()) {
                    return Mono.error(new IllegalStateException("无人参与"));
                }

                var winners = drawForParticipants(participants, prizes);
                var status = getStatus(activity);
                status.setState(State.DRAWN);
                status.setDrawnTime(Instant.now());
                status.setWinners(winners);

                return client.update(activity);
            });
    }

    /**
     * 为所有参与者抽奖（定时开奖用）
     */
    private List<Winner> drawForParticipants(List<LotteryParticipant> participants, List<Prize> prizes) {
        // 构建奖品剩余数量 Map
        var remaining = prizes.stream()
            .collect(Collectors.toMap(
                Prize::getName,
                p -> Objects.requireNonNullElse(p.getQuantity(), 0),
                Integer::sum,
                HashMap::new
            ));

        // 随机打乱参与者
        var shuffled = new ArrayList<>(participants);
        Collections.shuffle(shuffled, ThreadLocalRandom.current());

        var random = ThreadLocalRandom.current();
        var winners = new ArrayList<Winner>();

        for (var participant : shuffled) {
            // 检查是否还有奖品
            if (remaining.values().stream().noneMatch(v -> v > 0)) {
                break;
            }

            // 按概率抽奖
            drawByProbability(prizes, remaining, random).ifPresent(prize -> {
                var spec = participant.getSpec();
                winners.add(createWinner(
                    Objects.requireNonNullElse(spec.getUsername(), spec.getEmail()),
                    prize.getName(),
                    Instant.now()
                ));
                remaining.merge(prize.getName(), -1, Integer::sum);
            });
        }

        return winners;
    }

    /**
     * 按概率抽奖（通用方法）
     * @param prizes 奖品列表
     * @param remaining 剩余数量 Map（null 时使用 prize 自身的 remaining/quantity）
     * @param random 随机数生成器
     * @return 中奖的奖品
     */
    private Optional<Prize> drawByProbability(List<Prize> prizes,
                                               Map<String, Integer> remaining,
                                               Random random) {
        // 筛选可用奖品（有库存且有概率）
        record PrizeWithProb(Prize prize, int probability) {}

        var available = prizes.stream()
            .filter(p -> getRemainingCount(p, remaining) > 0)
            .filter(p -> getProbability(p) > 0)
            .map(p -> new PrizeWithProb(p, getProbability(p)))
            .toList();

        if (available.isEmpty()) {
            return Optional.empty();
        }

        int totalProb = available.stream().mapToInt(PrizeWithProb::probability).sum();
        int rand = random.nextInt(100);

        // 未中奖（随机数超出总概率范围）
        if (rand >= totalProb) {
            return Optional.empty();
        }

        // 按概率区间匹配
        int cumulative = 0;
        for (var item : available) {
            cumulative += item.probability();
            if (rand < cumulative) {
                return Optional.of(item.prize());
            }
        }

        return Optional.empty();
    }

    /**
     * 执行即时开奖（大转盘/刮刮乐）
     */
    private Mono<LotteryParticipant> executeInstantDraw(LotteryActivity activity,
                                                         String email, String displayName,
                                                         String token, String ipAddress) {
        var activityName = activity.getMetadata().getName();
        var prizes = activity.getSpec().getPrizes();

        if (prizes == null || prizes.isEmpty()) {
            return createParticipant(activityName, email, displayName, token, ipAddress, null);
        }

        var result = drawByProbability(prizes, null, ThreadLocalRandom.current());

        return result
            .map(prize -> updatePrizeRemaining(activity, prize.getName())
                .flatMap(ignored -> createParticipant(activityName, email, displayName, token, ipAddress, prize.getName())))
            .orElseGet(() -> createParticipant(activityName, email, displayName, token, ipAddress, null));
    }

    // ==================== 参与验证 ====================

    private Mono<Void> validateEmail(String email) {
        if (email == null || email.isBlank()) {
            return Mono.error(new IllegalArgumentException("邮箱不能为空"));
        }
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            return Mono.error(new IllegalArgumentException("邮箱格式不正确"));
        }
        return Mono.empty();
    }

    private Mono<Void> validateParticipation(LotteryActivity activity) {
        var spec = activity.getSpec();
        var status = getStatus(activity);

        if (spec.getParticipationType() != ParticipationType.NONE) {
            return Mono.error(new IllegalStateException("该活动不支持匿名参与"));
        }
        if (status.getState() != State.RUNNING) {
            return Mono.error(new IllegalStateException("活动未在进行中"));
        }

        var now = Instant.now();
        if (spec.getStartTime() != null && now.isBefore(spec.getStartTime())) {
            return Mono.error(new IllegalStateException("活动未开始"));
        }
        if (spec.getEndTime() != null && now.isAfter(spec.getEndTime())) {
            return Mono.error(new IllegalStateException("活动已结束"));
        }

        int current = Objects.requireNonNullElse(status.getParticipantCount(), 0);
        if (spec.getMaxParticipants() != null && current >= spec.getMaxParticipants()) {
            return Mono.error(new IllegalStateException("参与人数已满"));
        }

        return Mono.empty();
    }

    private Mono<Void> checkDuplicate(String activityName, String email) {
        return findByToken(generateToken(activityName, email))
            .flatMap(ignored -> Mono.<Void>error(new IllegalStateException("您已参与过此活动")))
            .switchIfEmpty(Mono.empty());
    }

    private Mono<LotteryParticipant> doParticipate(LotteryActivity activity, String email,
                                                    String displayName, String ipAddress) {
        var activityName = activity.getMetadata().getName();
        var token = generateToken(activityName, email);
        var lotteryType = activity.getSpec().getLotteryType();

        Mono<LotteryParticipant> participateMono = switch (lotteryType) {
            case WHEEL, DRAW -> executeInstantDraw(activity, email, displayName, token, ipAddress);
            case null, default -> createParticipant(activityName, email, displayName, token, ipAddress, null);
        };

        return participateMono.flatMap(p -> updateParticipantCount(activityName).thenReturn(p));
    }


    private LotteryActivityStatus getStatus(LotteryActivity activity) {
        var status = activity.getStatus();
        if (status == null) {
            status = new LotteryActivityStatus();
            status.setParticipantCount(0);
            activity.setStatus(status);
        }
        return status;
    }

    private int getRemainingCount(Prize prize, Map<String, Integer> remaining) {
        if (remaining != null) {
            return remaining.getOrDefault(prize.getName(), 0);
        }
        return Objects.requireNonNullElse(prize.getRemaining(),
            Objects.requireNonNullElse(prize.getQuantity(), 0));
    }

    private int getProbability(Prize prize) {
        return Objects.requireNonNullElse(prize.getProbability(), 0);
    }

    private Winner createWinner(String identifier, String prizeName, Instant winTime) {
        var winner = new Winner();
        winner.setIdentifier(identifier);
        winner.setPrizeName(prizeName);
        winner.setWinTime(winTime);
        return winner;
    }

    private Flux<LotteryParticipant> getParticipants(String activityName) {
        return client.listAll(LotteryParticipant.class,
            ListOptions.builder().fieldQuery(equal("spec.activityName", activityName)).build(),
            null);
    }

    private Mono<Winner> findWinnerFromActivity(String activityName, LotteryParticipantSpec spec) {
        return client.get(LotteryActivity.class, activityName)
            .flatMap(activity -> {
                var winners = Optional.ofNullable(activity.getStatus())
                    .map(LotteryActivityStatus::getWinners)
                    .orElse(List.of());

                var identifier = Objects.requireNonNullElse(spec.getUsername(), spec.getEmail());
                return winners.stream()
                    .filter(w -> identifier.equals(w.getIdentifier()))
                    .findFirst()
                    .map(Mono::just)
                    .orElse(Mono.empty());
            });
    }

    private Mono<LotteryActivity> updatePrizeRemaining(LotteryActivity activity, String prizeName) {
        activity.getSpec().getPrizes().stream()
            .filter(p -> p.getName().equals(prizeName))
            .findFirst()
            .ifPresent(prize -> {
                int current = getRemainingCount(prize, null);
                prize.setRemaining(Math.max(0, current - 1));
            });
        return client.update(activity);
    }

    private Mono<LotteryParticipant> createParticipant(String activityName, String email,
                                                        String displayName, String token,
                                                        String ipAddress, String prizeName) {
        var participant = new LotteryParticipant();
        participant.setMetadata(new Metadata());
        participant.getMetadata().setGenerateName("participant-");

        var spec = new LotteryParticipantSpec();
        spec.setActivityName(activityName);
        spec.setEmail(email);
        spec.setDisplayName(displayName);
        spec.setToken(token);
        spec.setParticipateTime(Instant.now());
        spec.setIpAddress(ipAddress);

        if (prizeName != null) {
            spec.setIsWinner(true);
            spec.setPrizeName(prizeName);
            spec.setWinTime(Instant.now());
        } else {
            spec.setIsWinner(false);
        }

        participant.setSpec(spec);
        return client.create(participant);
    }

    private Mono<Void> updateParticipantCount(String activityName) {
        return client.get(LotteryActivity.class, activityName)
            .flatMap(activity -> {
                var status = getStatus(activity);
                status.setParticipantCount(
                    Objects.requireNonNullElse(status.getParticipantCount(), 0) + 1
                );
                return client.update(activity);
            })
            .then();
    }
}
