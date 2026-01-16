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
import com.xhhao.lottery.service.LotteryNotificationService;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    private final ReactiveExtensionClient client;
    private final LotteryNotificationService notificationService;

    private static final String TOKEN_SALT = "lottery_plugin_salt_2024";
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[\\w.-]+@[\\w.-]+\\.[a-zA-Z]{2,}$");

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
            .flatMap(activity -> validateParticipation(activity, ParticipationType.NONE)
                .then(checkDuplicate(activityName, email))
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
                        .then(checkDuplicate(activityName, email))
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
                                    
                                    return checkDuplicate(activityName, email)
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
                    .then(checkDuplicate(activityName, email))
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
                            .then(checkDuplicate(activityName, email))
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
                    spec.getWinTime()
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

    private List<Winner> drawForParticipants(List<LotteryParticipant> participants, List<Prize> prizes) {
        var remaining = prizes.stream()
            .collect(Collectors.toMap(
                Prize::getName,
                p -> Objects.requireNonNullElse(p.getQuantity(), 0),
                Integer::sum,
                HashMap::new
            ));

        var shuffled = new ArrayList<>(participants);
        Collections.shuffle(shuffled, ThreadLocalRandom.current());

        var random = ThreadLocalRandom.current();
        var winners = new ArrayList<Winner>();

        for (var participant : shuffled) {
            if (remaining.values().stream().noneMatch(v -> v > 0)) {
                break;
            }

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

    private Optional<Prize> drawByProbability(List<Prize> prizes,
                                               Map<String, Integer> remaining,
                                               Random random) {
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

        if (rand >= totalProb) {
            return Optional.empty();
        }

        int cumulative = 0;
        for (var item : available) {
            cumulative += item.probability();
            if (rand < cumulative) {
                return Optional.of(item.prize());
            }
        }

        return Optional.empty();
    }

    private Mono<LotteryParticipant> executeInstantDraw(LotteryActivity activity,
                                                         String email, String displayName,
                                                         String username, String commentName,
                                                         String token, String ipAddress) {
        var prizes = activity.getSpec().getPrizes();

        if (prizes == null || prizes.isEmpty()) {
            return createParticipant(activity, email, displayName, username, commentName, token, ipAddress, null);
        }

        var result = drawByProbability(prizes, null, ThreadLocalRandom.current());

        return result
            .map(prize -> updatePrizeRemaining(activity, prize.getName())
                .flatMap(ignored -> createParticipant(activity, email, displayName, username, commentName, token, ipAddress, prize.getName())))
            .orElseGet(() -> createParticipant(activity, email, displayName, username, commentName, token, ipAddress, null));
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
                                                    String displayName, String username,
                                                    String commentName, String ipAddress) {
        var activityName = activity.getMetadata().getName();
        var token = generateToken(activityName, email);
        var lotteryType = activity.getSpec().getLotteryType();

        Mono<LotteryParticipant> participateMono = switch (lotteryType) {
            case WHEEL, DRAW -> executeInstantDraw(activity, email, displayName, username, commentName, token, ipAddress);
            case null, default -> createParticipant(activity, email, displayName, username, commentName, token, ipAddress, null);
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
