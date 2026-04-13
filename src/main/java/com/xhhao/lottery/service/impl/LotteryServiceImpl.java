package com.xhhao.lottery.service.impl;

import com.xhhao.lottery.entity.LotteryActivity;
import com.xhhao.lottery.entity.LotteryActivity.LotteryActivitySpec;
import com.xhhao.lottery.entity.LotteryActivity.LotteryActivityStatus;
import com.xhhao.lottery.entity.LotteryActivity.LotteryType;
import com.xhhao.lottery.entity.LotteryActivity.ManualAssignment;
import com.xhhao.lottery.entity.LotteryActivity.ParticipationType;
import com.xhhao.lottery.entity.LotteryActivity.Prize;
import com.xhhao.lottery.entity.LotteryActivity.State;
import com.xhhao.lottery.entity.LotteryActivity.Winner;
import com.xhhao.lottery.entity.LotteryParticipant;
import com.xhhao.lottery.entity.LotteryParticipant.LotteryParticipantSpec;
import com.xhhao.lottery.query.LotteryActivityQuery;
import com.xhhao.lottery.service.InstantLotteryStockService;
import com.xhhao.lottery.service.LotteryNotificationService;
import com.xhhao.lottery.service.LotteryService;
import com.xhhao.lottery.service.RedisConfigService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
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
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.Base64;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.content.Comment;
import com.xhhao.lottery.util.SecurityUtil;

import static run.halo.app.extension.index.query.Queries.equal;

@Service
@RequiredArgsConstructor
public class LotteryServiceImpl implements LotteryService {

    private static final String DUPLICATE_GUARD_KEY_PREFIX = "plugin:lottery:duplicate";
    private static final String PARTICIPANT_LIMIT_KEY_PREFIX = "plugin:lottery:participant-limit";
    private static final Duration REDIS_KEY_RETENTION = Duration.ofDays(7);
    private static final Duration REDIS_KEY_FALLBACK_TTL = Duration.ofDays(30);
    private static final String ACQUIRE_DUPLICATE_GUARD_SCRIPT = """
        if redis.call('EXISTS', KEYS[1]) == 1 then
            return 0
        end

        redis.call('SET', KEYS[1], ARGV[1])
        local ttl = tonumber(ARGV[2]) or 0
        if ttl > 0 then
            redis.call('EXPIRE', KEYS[1], ttl)
        end
        return 1
        """;
    private static final String RELEASE_DUPLICATE_GUARD_SCRIPT = """
        return redis.call('DEL', KEYS[1])
        """;
    private static final String ACQUIRE_PARTICIPANT_SLOT_SCRIPT = """
        local current = redis.call('GET', KEYS[1])
        if not current then
            current = tonumber(ARGV[1]) or 0
            redis.call('SET', KEYS[1], current)
        else
            current = tonumber(current) or 0
        end

        local max = tonumber(ARGV[2]) or 0
        local ttl = tonumber(ARGV[3]) or 0
        if current >= max then
            return 0
        end

        redis.call('INCR', KEYS[1])
        if ttl > 0 then
            redis.call('EXPIRE', KEYS[1], ttl)
        end
        return 1
        """;
    private static final String RELEASE_PARTICIPANT_SLOT_SCRIPT = """
        local current = tonumber(redis.call('GET', KEYS[1]) or '0')
        if current <= 0 then
            return 0
        end

        return redis.call('DECR', KEYS[1])
        """;

    private final ReactiveExtensionClient client;
    private final LotteryNotificationService notificationService;
    private final InstantLotteryStockService instantLotteryStockService;
    private final RedisConfigService redisConfigService;

    private static final String TOKEN_SALT = "lottery_plugin_salt_2024";
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[\\w.-]+@[\\w.-]+\\.[a-zA-Z]{2,}$");

    @Override
    public Mono<LotteryActivity> getActivity(String activityName) {
        return client.get(LotteryActivity.class, activityName)
            .flatMap(this::checkAndUpdateState)
            .flatMap(this::checkAndAutoDraw)
            .flatMap(this::enrichActivityMetrics);
    }

    @Override
    public Mono<ListResult<LotteryActivity>> listActivities(LotteryActivityQuery query) {
        return client.listBy(LotteryActivity.class, query.toListOptions(),
                PageRequestImpl.of(query.getPage(), query.getSize(), query.getSort()))
            .flatMap(result -> Flux.fromIterable(result.getItems())
                .flatMap(this::checkAndUpdateState)
                .flatMap(this::enrichActivityMetrics)
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
            .flatMap(activity -> validateParticipation(activity, ParticipationType.NONE)
                .then(checkDuplicate(activity, email))
                .then(doParticipate(activity, email, displayName, null, null, ipAddress)));
    }

    @Override
    public Mono<LotteryParticipant> participateWithLogin(String activityName, String ipAddress) {
        return getCurrentUser()
            .switchIfEmpty(Mono.error(new IllegalStateException("请先登录")))
            .flatMap(user -> {
                String email = user.getSpec().getEmail();
                String username = user.getMetadata().getName();
                String displayName = user.getSpec().getDisplayName();
                
                if (email == null || email.isBlank()) {
                    return Mono.error(new IllegalStateException("用户邮箱未设置"));
                }
                
                return client.get(LotteryActivity.class, activityName)
                    .switchIfEmpty(Mono.error(new IllegalArgumentException("活动不存在")))
                    .flatMap(activity -> validateParticipation(activity, ParticipationType.LOGIN)
                        .then(checkDuplicate(activity, email))
                        .then(doParticipate(activity, email, displayName, username, null, ipAddress)));
            });
    }

    @Override
    public Mono<LotteryParticipant> participateWithComment(String activityName, String postName, String ipAddress) {
        return ReactiveSecurityContextHolder.getContext()
            .flatMap(securityContext -> {
                Authentication authentication = securityContext.getAuthentication();
                
                if (authentication != null && !(authentication instanceof AnonymousAuthenticationToken)) {
                    String username = authentication.getName();
                    
                    return client.get(LotteryActivity.class, activityName)
                        .switchIfEmpty(Mono.error(new IllegalArgumentException("活动不存在")))
                        .flatMap(activity -> {
                            String targetPost = (postName != null && !postName.isBlank()) 
                                ? postName 
                                : activity.getSpec().getTargetPostName();
                                
                            if (targetPost == null || targetPost.isBlank()) {
                                return Mono.error(new IllegalStateException("无法确定关联文章"));
                            }
                            
                            return validateParticipation(activity, ParticipationType.COMMENT)
                                .then(getCurrentUser())
                                .flatMap(user -> {
                                    String email = user.getSpec().getEmail();
                                    String displayName = user.getSpec().getDisplayName();
                                    
                                    if (email == null || email.isBlank()) {
                                        return Mono.error(new IllegalStateException("用户邮箱未设置"));
                                    }
                                    
                                    return checkDuplicate(activity, email)
                                        .then(findCommentByUsername(targetPost, username))
                                        .switchIfEmpty(Mono.error(new IllegalStateException("请先在文章下评论")))
                                        .flatMap(comment -> {
                                            String commentName = comment.getMetadata().getName();
                                            return doParticipate(activity, email, displayName, username, commentName, ipAddress);
                                        });
                                });
                        });
                }
                
                return Mono.error(new IllegalStateException("评论参与需要提供邮箱或登录"));
            })
            .switchIfEmpty(Mono.error(new IllegalStateException("评论参与需要提供邮箱或登录")));
    }

    @Override
    public Mono<LotteryParticipant> participateWithCommentByEmail(String activityName, String email, String postName, String ipAddress) {
        return validateEmail(email)
            .then(client.get(LotteryActivity.class, activityName))
            .switchIfEmpty(Mono.error(new IllegalArgumentException("活动不存在")))
            .flatMap(activity -> {
                String targetPost = (postName != null && !postName.isBlank()) 
                    ? postName 
                    : activity.getSpec().getTargetPostName();
                    
                if (targetPost == null || targetPost.isBlank()) {
                    return Mono.error(new IllegalStateException("无法确定关联文章"));
                }
                
                return validateParticipation(activity, ParticipationType.COMMENT)
                    .then(checkDuplicate(activity, email))
                    .then(findCommentByEmail(targetPost, email))
                    .switchIfEmpty(Mono.error(new IllegalStateException("请先使用此邮箱在文章下评论")))
                    .flatMap(comment -> {
                        String commentName = comment.getMetadata().getName();
                        String displayName = comment.getSpec().getOwner().getDisplayName();
                        return doParticipate(activity, email, displayName, null, commentName, ipAddress);
                    });
            });
    }

    @Override
    public Mono<LotteryParticipant> participateWithLoginAndComment(String activityName, String postName, String ipAddress) {
        return getCurrentUser()
            .switchIfEmpty(Mono.error(new IllegalStateException("请先登录")))
            .flatMap(user -> {
                String email = user.getSpec().getEmail();
                String username = user.getMetadata().getName();
                String displayName = user.getSpec().getDisplayName();
                
                if (email == null || email.isBlank()) {
                    return Mono.error(new IllegalStateException("用户邮箱未设置"));
                }
                
                return client.get(LotteryActivity.class, activityName)
                    .switchIfEmpty(Mono.error(new IllegalArgumentException("活动不存在")))
                    .flatMap(activity -> {
                        var targetPost = (postName != null && !postName.isBlank()) 
                            ? postName 
                            : activity.getSpec().getTargetPostName();
                            
                        if (targetPost == null || targetPost.isBlank()) {
                            return Mono.error(new IllegalStateException("无法确定关联文章"));
                        }
                        
                        return validateParticipation(activity, ParticipationType.LOGIN_AND_COMMENT)
                            .then(checkDuplicate(activity, email))
                            .then(findCommentByUsername(targetPost, username))
                            .switchIfEmpty(Mono.error(new IllegalStateException("请先在文章下评论")))
                            .flatMap(comment -> {
                                String commentName = comment.getMetadata().getName();
                                return doParticipate(activity, email, displayName, username, commentName, ipAddress);
                            });
                    });
            });
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
            if (Boolean.TRUE.equals(spec.getIsWinner())) {
                return Mono.just(createWinner(
                    Objects.requireNonNullElse(spec.getUsername(), spec.getEmail()),
                    spec.getPrizeName(),
                    spec.getWinTime(),
                    spec.getToken(),
                    "INSTANT"
                ));
            }
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
        var drawTime = spec.getDrawTime() != null ? spec.getDrawTime() : spec.getEndTime();

        if (drawTime != null && Instant.now().isAfter(drawTime)
            && (status.getState() == State.RUNNING || status.getState() == State.ENDED)) {
            return executeDraw(activity);
        }
        return Mono.just(activity);
    }

    private Mono<LotteryActivity> executeDraw(LotteryActivity activity) {
        var prizes = activity.getSpec().getPrizes();
        if (prizes == null || prizes.isEmpty()) {
            return Mono.error(new IllegalStateException("未设置奖品"));
        }

        return getParticipants(activity.getMetadata().getName())
            .collectList()
            .flatMap(participants -> ensureManualParticipants(activity, participants))
            .flatMap(allParticipants -> {
                if (allParticipants.isEmpty()) {
                    return Mono.error(new IllegalStateException("无人参与"));
                }

                var status = getStatus(activity);
                var existingWinners = new ArrayList<>(
                    Optional.ofNullable(status.getWinners()).orElse(List.of())
                );
                var winners = drawForParticipants(
                    activity,
                    allParticipants,
                    prizes,
                    existingWinners
                );
                if (winners.isEmpty()) {
                    return Mono.error(new IllegalStateException("没有可开奖的奖项"));
                }
                existingWinners.addAll(winners);
                status.setDrawnTime(Instant.now());
                status.setWinners(existingWinners);
                status.setState(State.DRAWN);

                return client.update(activity);
            });
    }

    private Mono<List<LotteryParticipant>> ensureManualParticipants(LotteryActivity activity,
                                                                    List<LotteryParticipant> participants) {
        var assignments = Optional.ofNullable(activity.getSpec().getManualAssignments()).orElse(List.of());
        if (assignments.isEmpty()) {
            return Mono.just(participants);
        }

        var participantByToken = participants.stream()
            .filter(participant -> participant.getSpec() != null)
            .filter(participant -> participant.getSpec().getToken() != null)
            .collect(Collectors.toMap(
                participant -> participant.getSpec().getToken(),
                participant -> participant,
                (left, right) -> left
            ));

        var participantByIdentifier = participants.stream()
            .filter(participant -> participant.getSpec() != null)
            .filter(participant -> resolveParticipantIdentifier(participant.getSpec()) != null)
            .collect(Collectors.toMap(
                participant -> Objects.requireNonNull(resolveParticipantIdentifier(participant.getSpec())).toLowerCase(),
                participant -> participant,
                (left, right) -> left
            ));

        return Flux.fromIterable(assignments)
            .concatMap(assignment -> {
                if (assignment == null) {
                    return Mono.empty();
                }

                if (StringUtils.isNotBlank(assignment.getParticipantToken())
                    && participantByToken.containsKey(assignment.getParticipantToken())) {
                    return Mono.empty();
                }

                var identifier = resolveManualAssignmentIdentifier(assignment);
                if (StringUtils.isBlank(identifier)) {
                    return Mono.error(new IllegalStateException("指定中奖人缺少标识信息"));
                }

                if (participantByIdentifier.containsKey(identifier.toLowerCase())) {
                    return Mono.empty();
                }

                return createManualParticipant(activity, assignment)
                    .doOnNext(created -> {
                        if (created.getSpec() == null) {
                            return;
                        }
                        var createdSpec = created.getSpec();
                        if (StringUtils.isNotBlank(createdSpec.getToken())) {
                            participantByToken.put(createdSpec.getToken(), created);
                        }
                        var createdIdentifier = resolveParticipantIdentifier(createdSpec);
                        if (StringUtils.isNotBlank(createdIdentifier)) {
                            participantByIdentifier.put(createdIdentifier.toLowerCase(), created);
                        }
                    })
                    .then();
            })
            .thenMany(getParticipants(activity.getMetadata().getName()))
            .collectList();
    }

    private List<Winner> drawForParticipants(LotteryActivity activity,
                                             List<LotteryParticipant> participants,
                                             List<Prize> prizes,
                                             List<Winner> existingWinners) {
        var consumed = existingWinners.stream()
            .map(Winner::getPrizeName)
            .filter(Objects::nonNull)
            .collect(Collectors.groupingBy(prizeName -> prizeName, Collectors.counting()));
        var remaining = buildRemainingMap(prizes, consumed);
        var alreadyAwardedTokens = existingWinners.stream()
            .map(Winner::getSourceToken)
            .filter(StringUtils::isNotBlank)
            .collect(Collectors.toSet());

        var winners = applyManualAssignments(
            activity,
            participants,
            remaining,
            alreadyAwardedTokens
        );

        var shuffled = new ArrayList<>(participants);
        var assignedTokens = Stream.concat(
                alreadyAwardedTokens.stream(),
                winners.stream().map(Winner::getSourceToken).filter(Objects::nonNull)
            )
            .collect(Collectors.toSet());
        shuffled.removeIf(participant -> participant.getSpec() == null
            || assignedTokens.contains(participant.getSpec().getToken()));
        Collections.shuffle(shuffled, ThreadLocalRandom.current());

        for (var prize : prizes) {
            int slots = remaining.getOrDefault(prize.getName(), 0);
            while (slots > 0 && !shuffled.isEmpty()) {
                var participant = shuffled.remove(0);
                var spec = participant.getSpec();
                winners.add(createWinner(
                    Objects.requireNonNullElse(spec.getUsername(), spec.getEmail()),
                    prize.getName(),
                    Instant.now(),
                    spec.getToken(),
                    "RANDOM"
                ));
                remaining.merge(prize.getName(), -1, Integer::sum);
                slots--;
            }
        }

        return winners;
    }

    private List<Winner> applyManualAssignments(
        LotteryActivity activity,
        List<LotteryParticipant> participants,
        Map<String, Integer> remaining,
        Set<String> alreadyAwardedTokens
    ) {
        var assignments = Optional.ofNullable(activity.getSpec().getManualAssignments()).orElse(List.of());
        if (assignments.isEmpty()) {
            return new ArrayList<>();
        }

        var participantByToken = participants.stream()
            .filter(participant -> participant.getSpec() != null && participant.getSpec().getToken() != null)
            .collect(Collectors.toMap(
                participant -> participant.getSpec().getToken(),
                participant -> participant,
                (left, right) -> left
            ));
        var participantByIdentifier = participants.stream()
            .filter(participant -> participant.getSpec() != null)
            .filter(participant -> resolveParticipantIdentifier(participant.getSpec()) != null)
            .collect(Collectors.toMap(
                participant -> Objects.requireNonNull(resolveParticipantIdentifier(participant.getSpec())).toLowerCase(),
                participant -> participant,
                (left, right) -> left
            ));

        var assignedTokens = new HashSet<String>(alreadyAwardedTokens);
        var winners = new ArrayList<Winner>();

        for (var assignment : assignments) {
            if (assignment == null || assignment.getPrizeName() == null) {
                continue;
            }

            if (StringUtils.isBlank(assignment.getParticipantToken())
                && StringUtils.isBlank(resolveManualAssignmentIdentifier(assignment))) {
                continue;
            }

            var prizeName = assignment.getPrizeName();
            if (!remaining.containsKey(prizeName)) {
                throw new IllegalStateException("指定中奖奖品不存在: " + prizeName);
            }

            if (remaining.getOrDefault(prizeName, 0) <= 0) {
                throw new IllegalStateException("奖品 " + prizeName + " 的指定中奖人数超过奖品数量");
            }

            LotteryParticipant participant = null;
            if (StringUtils.isNotBlank(assignment.getParticipantToken())) {
                participant = participantByToken.get(assignment.getParticipantToken());
            }
            if (participant == null) {
                var identifier = resolveManualAssignmentIdentifier(assignment);
                if (StringUtils.isNotBlank(identifier)) {
                    participant = participantByIdentifier.get(identifier.toLowerCase());
                }
            }
            if (participant == null) {
                throw new IllegalStateException("指定中奖人不存在");
            }

            if (!assignedTokens.add(participant.getSpec().getToken())) {
                throw new IllegalStateException("同一参与人不能被多个奖品重复指定");
            }

            var spec = participant.getSpec();
            winners.add(createWinner(
                Objects.requireNonNullElse(spec.getUsername(), spec.getEmail()),
                prizeName,
                Instant.now(),
                spec.getToken(),
                "MANUAL"
            ));
            remaining.merge(prizeName, -1, Integer::sum);
        }

        return winners;
    }

    private Mono<LotteryParticipant> executeInstantDraw(LotteryActivity activity,
                                                         String email, String displayName,
                                                         String username, String commentName,
                                                         String token, String ipAddress) {
        return instantLotteryStockService.reservePrize(activity)
            .flatMap(result -> result
                .map(prize -> createParticipant(
                    activity, email, displayName, username, commentName, token, ipAddress, prize.getName()
                ).onErrorResume(error -> instantLotteryStockService.releasePrize(activity, prize.getName())
                    .then(Mono.error(error))))
                .orElseGet(() -> createParticipant(
                    activity, email, displayName, username, commentName, token, ipAddress, null
                )));
    }

    private Mono<Void> validateEmail(String email) {
        if (email == null || email.isBlank()) {
            return Mono.error(new IllegalArgumentException("邮箱不能为空"));
        }
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            return Mono.error(new IllegalArgumentException("邮箱格式不正确"));
        }
        return Mono.empty();
    }

    private Mono<Void> validateParticipation(LotteryActivity activity, ParticipationType expectedType) {
        var spec = activity.getSpec();
        var status = getStatus(activity);

        if (spec.getParticipationType() != expectedType) {
            String msg = switch (spec.getParticipationType()) {
                case NONE -> "该活动仅支持匿名参与";
                case LOGIN -> "该活动需要登录参与";
                case COMMENT -> "该活动需要评论后参与";
                case LOGIN_AND_COMMENT -> "该活动需要登录并评论后参与";
            };
            return Mono.error(new IllegalStateException(msg));
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

        return countParticipants(activity.getMetadata().getName())
            .flatMap(current -> {
                status.setParticipantCount(current);
                if (spec.getMaxParticipants() != null && current >= spec.getMaxParticipants()) {
                    return Mono.<Void>error(new IllegalStateException("参与人数已满"));
                }
                return Mono.empty();
            });
    }

    private Mono<Void> checkDuplicate(LotteryActivity activity, String email) {
        if (Boolean.TRUE.equals(activity.getSpec().getAllowDuplicate())) {
            return Mono.empty();
        }

        return findByToken(generateToken(activity.getMetadata().getName(), email))
            .flatMap(ignored -> Mono.<Void>error(new IllegalStateException("您已参与过此活动")))
            .switchIfEmpty(Mono.empty());
    }

    private Mono<LotteryParticipant> doParticipate(LotteryActivity activity, String email,
                                                    String displayName, String username,
                                                    String commentName, String ipAddress) {
        var activityName = activity.getMetadata().getName();
        var token = generateToken(activityName, email);
        var lotteryType = activity.getSpec().getLotteryType();

        Mono<LotteryParticipant> participateMono = switch (lotteryType) {
            case WHEEL, DRAW -> executeInstantDraw(activity, email, displayName, username, commentName, token, ipAddress);
            case null, default -> createParticipant(activity, email, displayName, username, commentName, token, ipAddress, null);
        };

        return acquireDuplicateGuard(activity, token)
            .flatMap(duplicateGuard -> acquireParticipantSlot(activity)
                .flatMap(slot -> participateMono.onErrorResume(error -> releaseParticipantSlot(slot)
                    .then(releaseDuplicateGuard(duplicateGuard))
                    .then(Mono.error(error))))
                .onErrorResume(error -> releaseDuplicateGuard(duplicateGuard).then(Mono.error(error))));
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

    private Winner createWinner(String identifier, String prizeName, Instant winTime,
                                String sourceToken, String drawSource) {
        var winner = new Winner();
        winner.setIdentifier(identifier);
        winner.setPrizeName(prizeName);
        winner.setWinTime(winTime);
        winner.setSourceToken(sourceToken);
        winner.setDrawSource(drawSource);
        return winner;
    }

    private Flux<LotteryParticipant> getParticipants(String activityName) {
        return client.listAll(LotteryParticipant.class,
            ListOptions.builder().fieldQuery(equal("spec.activityName", activityName)).build(),
            null);
    }

    private Mono<Integer> countParticipants(String activityName) {
        return client.listBy(LotteryParticipant.class,
                ListOptions.builder().fieldQuery(equal("spec.activityName", activityName)).build(),
                PageRequestImpl.ofSize(1))
            .map(result -> Math.toIntExact(result.getTotal()))
            .defaultIfEmpty(0);
    }

    private Mono<ParticipantSlotReservation> acquireParticipantSlot(LotteryActivity activity) {
        var maxParticipants = Optional.ofNullable(activity.getSpec())
            .map(LotteryActivitySpec::getMaxParticipants)
            .orElse(null);
        if (maxParticipants == null) {
            return Mono.just(ParticipantSlotReservation.noop());
        }

        var activityName = activity.getMetadata().getName();
        var key = participantLimitKey(activityName);
        var ttlSeconds = redisTtlSeconds(activity);
        return countParticipants(activityName)
            .flatMap(currentCount -> redisConfigService.getRedisConnection()
                .switchIfEmpty(Mono.error(new IllegalStateException("参与人数上限依赖 Redis，请先完成 Redis 连接测试。")))
                .flatMap(connection -> Mono.fromFuture(connection.async()
                        .eval(
                            ACQUIRE_PARTICIPANT_SLOT_SCRIPT,
                            io.lettuce.core.ScriptOutputType.INTEGER,
                            new String[]{key},
                            Integer.toString(currentCount),
                            Integer.toString(maxParticipants),
                            Long.toString(ttlSeconds)
                        )
                        .toCompletableFuture())
                    .map(Number.class::cast)
                    .flatMap(result -> result.intValue() > 0
                        ? Mono.just(new ParticipantSlotReservation(true, activityName))
                        : Mono.error(new IllegalStateException("参与人数已满")))));
    }

    private Mono<DuplicateParticipationGuard> acquireDuplicateGuard(LotteryActivity activity, String token) {
        if (Boolean.TRUE.equals(activity.getSpec().getAllowDuplicate())) {
            return Mono.just(DuplicateParticipationGuard.noop());
        }

        var key = duplicateGuardKey(activity.getMetadata().getName(), token);
        var ttlSeconds = redisTtlSeconds(activity);
        return redisConfigService.getRedisConnection()
            .switchIfEmpty(Mono.error(new IllegalStateException("防重复参与依赖 Redis，请先完成 Redis 连接测试。")))
            .flatMap(connection -> Mono.fromFuture(connection.async()
                    .eval(
                        ACQUIRE_DUPLICATE_GUARD_SCRIPT,
                        io.lettuce.core.ScriptOutputType.INTEGER,
                        new String[]{key},
                        token,
                        Long.toString(ttlSeconds)
                    )
                    .toCompletableFuture())
                .map(Number.class::cast)
                .flatMap(result -> result.intValue() > 0
                    ? Mono.just(new DuplicateParticipationGuard(true, key))
                    : Mono.error(new IllegalStateException("您已参与过此活动"))));
    }

    private Mono<Void> releaseDuplicateGuard(DuplicateParticipationGuard guard) {
        if (guard == null || !guard.acquired()) {
            return Mono.empty();
        }

        return redisConfigService.getRedisConnection()
            .flatMap(connection -> Mono.fromFuture(connection.async()
                    .eval(
                        RELEASE_DUPLICATE_GUARD_SCRIPT,
                        io.lettuce.core.ScriptOutputType.INTEGER,
                        new String[]{guard.key()}
                    )
                    .toCompletableFuture())
                .then())
            .onErrorResume(throwable -> Mono.empty());
    }

    private Mono<Void> releaseParticipantSlot(ParticipantSlotReservation reservation) {
        if (reservation == null || !reservation.acquired()) {
            return Mono.empty();
        }

        return redisConfigService.getRedisConnection()
            .flatMap(connection -> Mono.fromFuture(connection.async()
                    .eval(
                        RELEASE_PARTICIPANT_SLOT_SCRIPT,
                        io.lettuce.core.ScriptOutputType.INTEGER,
                        new String[]{participantLimitKey(reservation.activityName())}
                    )
                    .toCompletableFuture())
                .then())
            .onErrorResume(throwable -> Mono.empty());
    }

    private String participantLimitKey(String activityName) {
        var encodedActivityName = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(activityName.getBytes(StandardCharsets.UTF_8));
        return PARTICIPANT_LIMIT_KEY_PREFIX + ":" + encodedActivityName;
    }

    private String duplicateGuardKey(String activityName, String token) {
        var encodedActivityName = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(activityName.getBytes(StandardCharsets.UTF_8));
        return DUPLICATE_GUARD_KEY_PREFIX + ":" + encodedActivityName + ":" + token;
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

    private Mono<LotteryActivity> enrichActivityMetrics(LotteryActivity activity) {
        var activityName = activity.getMetadata().getName();
        return Mono.zip(
                countParticipants(activityName),
                calculatePrizeRemaining(activity)
            )
            .map(tuple -> {
                var status = getStatus(activity);
                status.setParticipantCount(tuple.getT1());
                applyPrizeRemaining(activity, tuple.getT2());
                return activity;
            });
    }

    private Mono<Map<String, Integer>> calculatePrizeRemaining(LotteryActivity activity) {
        var prizes = Optional.ofNullable(activity.getSpec())
            .map(LotteryActivitySpec::getPrizes)
            .orElse(List.of());
        if (prizes.isEmpty()) {
            return Mono.just(Map.of());
        }

        var lotteryType = activity.getSpec().getLotteryType();
        if (lotteryType == LotteryType.WHEEL || lotteryType == LotteryType.DRAW) {
            return getInstantWinnerCounts(activity.getMetadata().getName())
                .map(consumed -> buildRemainingMap(prizes, consumed));
        }

        var consumed = Optional.ofNullable(activity.getStatus())
            .map(LotteryActivityStatus::getWinners)
            .orElse(List.of())
            .stream()
            .map(Winner::getPrizeName)
            .filter(Objects::nonNull)
            .collect(Collectors.groupingBy(prizeName -> prizeName, Collectors.counting()));
        return Mono.just(buildRemainingMap(prizes, consumed));
    }

    private Mono<Map<String, Long>> getInstantWinnerCounts(String activityName) {
        return getParticipants(activityName)
            .filter(participant -> Boolean.TRUE.equals(participant.getSpec().getIsWinner()))
            .map(participant -> participant.getSpec().getPrizeName())
            .filter(Objects::nonNull)
            .collect(Collectors.groupingBy(prizeName -> prizeName, Collectors.counting()));
    }

    private Map<String, Integer> buildRemainingMap(List<Prize> prizes, Map<String, Long> consumed) {
        return prizes.stream()
            .collect(Collectors.toMap(
                Prize::getName,
                prize -> {
                    int quantity = Objects.requireNonNullElse(prize.getQuantity(), 0);
                    long used = consumed.getOrDefault(prize.getName(), 0L);
                    return Math.max(0, quantity - Math.toIntExact(used));
                },
                (left, right) -> right,
                HashMap::new
            ));
    }

    private void applyPrizeRemaining(LotteryActivity activity, Map<String, Integer> remainingMap) {
        var prizes = Optional.ofNullable(activity.getSpec())
            .map(LotteryActivitySpec::getPrizes)
            .orElse(List.of());
        prizes.forEach(prize -> prize.setRemaining(
            remainingMap.getOrDefault(
                prize.getName(),
                Objects.requireNonNullElse(prize.getQuantity(), 0)
            )
        ));
    }

    private record ParticipantSlotReservation(boolean acquired, String activityName) {
        static ParticipantSlotReservation noop() {
            return new ParticipantSlotReservation(false, null);
        }
    }

    private record DuplicateParticipationGuard(boolean acquired, String key) {
        static DuplicateParticipationGuard noop() {
            return new DuplicateParticipationGuard(false, null);
        }
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

    private Mono<LotteryParticipant> createParticipant(LotteryActivity activity, String email,
                                                        String displayName, String username,
                                                        String commentName, String token,
                                                        String ipAddress, String prizeName) {
        
        var participant = new LotteryParticipant();
        participant.setMetadata(new Metadata());
        participant.getMetadata().setGenerateName("participant-");

        var spec = new LotteryParticipantSpec();
        spec.setActivityName(activity.getMetadata().getName());
        spec.setActivityTitle(activity.getSpec().getTitle());
        spec.setEmail(email);
        spec.setDisplayName(displayName);
        spec.setUsername(username);
        spec.setCommentName(commentName);
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
        return client.create(participant)
            .flatMap(p -> {
                Mono<Void> notification;
                var lotteryType = activity.getSpec().getLotteryType();
                boolean isInstantDraw = lotteryType == LotteryType.WHEEL || lotteryType == LotteryType.DRAW;
                
                
                if (isInstantDraw) {
                    if (prizeName != null) {
                        notification = notificationService.sendWinningNotification(p, activity, prizeName);
                    } else {
                        notification = notificationService.sendInstantNoPrizeNotification(p, activity);
                    }
                } else if (prizeName != null) {
                    notification = notificationService.sendWinningNotification(p, activity, prizeName);
                } else {
                    notification = notificationService.sendParticipateNotification(p, activity);
                }
                return notification.onErrorResume(e -> Mono.empty()).thenReturn(p);
            });
    }

    private Mono<LotteryParticipant> createManualParticipant(LotteryActivity activity,
                                                             LotteryActivity.ManualAssignment assignment) {
        var identifier = resolveManualAssignmentIdentifier(assignment);
        if (StringUtils.isBlank(identifier)) {
            return Mono.error(new IllegalStateException("指定中奖人缺少标识信息"));
        }

        var token = generateToken(activity.getMetadata().getName(), identifier);
        return findByToken(token)
            .switchIfEmpty(Mono.defer(() -> {
                var participant = new LotteryParticipant();
                participant.setMetadata(new Metadata());
                participant.getMetadata().setGenerateName("participant-");

                var spec = new LotteryParticipantSpec();
                spec.setActivityName(activity.getMetadata().getName());
                spec.setActivityTitle(activity.getSpec().getTitle());
                if (identifier.contains("@")) {
                    spec.setEmail(identifier);
                } else {
                    spec.setUsername(identifier);
                }
                spec.setDisplayName(StringUtils.defaultIfBlank(
                    assignment.getParticipantDisplayName(),
                    identifier
                ));
                spec.setToken(token);
                spec.setParticipateTime(Instant.now());
                spec.setIpAddress("manual-assignment");
                spec.setIsWinner(false);

                participant.setSpec(spec);
                return client.create(participant);
            }));
    }

    private String resolveManualAssignmentIdentifier(LotteryActivity.ManualAssignment assignment) {
        if (assignment == null) {
            return null;
        }
        return StringUtils.firstNonBlank(
            assignment.getParticipantIdentifier(),
            assignment.getParticipantDisplayName()
        );
    }

    private String resolveParticipantIdentifier(LotteryParticipantSpec spec) {
        if (spec == null) {
            return null;
        }
        return StringUtils.firstNonBlank(spec.getUsername(), spec.getEmail());
    }

    private Mono<User> getCurrentUser() {
        return SecurityUtil.getCurrentUser(client);
    }

    private Mono<Comment> findCommentByEmail(String postName, String email) {
        return client.list(Comment.class, comment -> {
                var ref = comment.getSpec().getSubjectRef();
                var owner = comment.getSpec().getOwner();
                return ref != null 
                    && postName.equals(ref.getName())
                    && "Email".equals(owner.getKind())
                    && email.equalsIgnoreCase(owner.getName());
            }, null)
            .next();
    }

    private Mono<Comment> findCommentByUsername(String postName, String username) {
        return client.list(Comment.class, comment -> {
                var ref = comment.getSpec().getSubjectRef();
                var owner = comment.getSpec().getOwner();
                return ref != null 
                    && postName.equals(ref.getName())
                    && "User".equals(owner.getKind())
                    && username.equals(owner.getName());
            }, null)
            .next();
    }

    @Override
    public Mono<CommentCheckResult> checkComment(String postName, String email) {
        return ReactiveSecurityContextHolder.getContext()
            .flatMap(securityContext -> {
                Authentication authentication = securityContext.getAuthentication();
                
                if (authentication != null && !(authentication instanceof AnonymousAuthenticationToken)) {
                    var username = authentication.getName();
                    return findCommentByUsername(postName, username)
                        .map(comment -> {
                            var result = new CommentCheckResult();
                            result.setHasCommented(true);
                            result.setIsLoggedIn(true);
                            result.setMessage("已评论，可以参与");
                            return result;
                        })
                        .switchIfEmpty(Mono.fromSupplier(() -> {
                            var result = new CommentCheckResult();
                            result.setHasCommented(false);
                            result.setIsLoggedIn(true);
                            result.setMessage("请先在文章下评论");
                            return result;
                        }));
                }
                
                if (email != null && !email.isBlank()) {
                    return findCommentByEmail(postName, email)
                        .map(comment -> {
                            var result = new CommentCheckResult();
                            result.setHasCommented(true);
                            result.setIsLoggedIn(false);
                            result.setEmail(email);
                            result.setMessage("已评论，可以参与");
                            return result;
                        })
                        .switchIfEmpty(Mono.fromSupplier(() -> {
                            var result = new CommentCheckResult();
                            result.setHasCommented(false);
                            result.setIsLoggedIn(false);
                            result.setEmail(email);
                            result.setMessage("请先使用此邮箱在文章下评论");
                            return result;
                        }));
                }
                
                var result = new CommentCheckResult();
                result.setHasCommented(false);
                result.setIsLoggedIn(false);
                result.setMessage("请先评论或登录");
                return Mono.just(result);
            })
            .switchIfEmpty(Mono.fromSupplier(() -> {
                if (email != null && !email.isBlank()) {
                    var result = new CommentCheckResult();
                    result.setHasCommented(false);
                    result.setIsLoggedIn(false);
                    result.setMessage("请先评论或登录");
                    return result;
                }
                var result = new CommentCheckResult();
                result.setHasCommented(false);
                result.setIsLoggedIn(false);
                result.setMessage("请先评论或登录");
                return result;
            }));
    }
}
