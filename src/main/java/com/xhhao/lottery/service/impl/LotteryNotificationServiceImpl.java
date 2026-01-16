package com.xhhao.lottery.service.impl;

import com.xhhao.lottery.entity.LotteryActivity;
import com.xhhao.lottery.entity.LotteryParticipant;
import com.xhhao.lottery.service.LotteryNotificationService;
import com.xhhao.lottery.service.SettingConfigGetter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.notification.Reason.Subject;
import run.halo.app.core.extension.notification.Subscription;
import run.halo.app.notification.NotificationCenter;
import run.halo.app.notification.NotificationReasonEmitter;
import run.halo.app.notification.UserIdentity;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class LotteryNotificationServiceImpl implements LotteryNotificationService {

    private final SettingConfigGetter settingConfigGetter;
    private final NotificationReasonEmitter reasonEmitter;
    private final NotificationCenter notificationCenter;

    private static final DateTimeFormatter DATE_FORMATTER = 
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final String REASON_TYPE_PARTICIPATE = "lottery-participate";
    private static final String REASON_TYPE_WINNING = "lottery-winning";
    private static final String REASON_TYPE_INSTANT_NO_PRIZE = "lottery-instant-no-prize";

    @Override
    public Mono<Void> sendParticipateNotification(LotteryParticipant participant, LotteryActivity activity) {
        return settingConfigGetter.getNotificationConfig()
            .filter(config -> Boolean.TRUE.equals(config.getEnableParticipateNotification()))
            .flatMap(config -> {
                var spec = participant.getSpec();
                String email = spec.getEmail();
                if (email == null || email.isBlank()) {
                    return Mono.empty();
                }
                
                String participantName = Objects.requireNonNullElse(
                    spec.getDisplayName(), 
                    Objects.requireNonNullElse(spec.getUsername(), email)
                );

                var userIdentity = UserIdentity.anonymousWithEmail(email);
                
                var subscriber = new Subscription.Subscriber();
                subscriber.setName(userIdentity.name());
                
                var interestReason = new Subscription.InterestReason();
                interestReason.setReasonType(REASON_TYPE_PARTICIPATE);
                interestReason.setExpression("props.participantEmail == '" + email + "'");

                var subject = Subject.builder()
                    .apiVersion("lottery.xhhao.com/v1alpha1")
                    .kind("LotteryParticipant")
                    .name(participant.getMetadata().getName())
                    .title(activity.getSpec().getTitle())
                    .build();

                return notificationCenter.subscribe(subscriber, interestReason)
                    .then(reasonEmitter.emit(REASON_TYPE_PARTICIPATE,
                        builder -> builder
                            .author(userIdentity)
                            .subject(subject)
                            .attribute("activityTitle", activity.getSpec().getTitle())
                            .attribute("activityName", activity.getMetadata().getName())
                            .attribute("participantEmail", email)
                            .attribute("participantName", participantName)
                            .attribute("participateTime", DATE_FORMATTER.format(spec.getParticipateTime()))
                    ));
            })
            .onErrorResume(e -> Mono.empty())
            .then();
    }

    @Override
    public Mono<Void> sendWinningNotification(LotteryParticipant participant, LotteryActivity activity, String prizeName) {
        return settingConfigGetter.getNotificationConfig()
            .filter(config -> Boolean.TRUE.equals(config.getEnableWinningNotification()))
            .flatMap(config -> {
                var spec = participant.getSpec();
                String email = spec.getEmail();
                if (email == null || email.isBlank()) {
                    return Mono.empty();
                }
                
                String participantName = Objects.requireNonNullElse(
                    spec.getDisplayName(), 
                    Objects.requireNonNullElse(spec.getUsername(), email)
                );

                var userIdentity = UserIdentity.anonymousWithEmail(email);
                
                var subscriber = new Subscription.Subscriber();
                subscriber.setName(userIdentity.name());
                
                var interestReason = new Subscription.InterestReason();
                interestReason.setReasonType(REASON_TYPE_WINNING);
                interestReason.setExpression("props.participantEmail == '" + email + "'");

                var subject = Subject.builder()
                    .apiVersion("lottery.xhhao.com/v1alpha1")
                    .kind("LotteryParticipant")
                    .name(participant.getMetadata().getName())
                    .title(activity.getSpec().getTitle())
                    .build();

                return notificationCenter.subscribe(subscriber, interestReason)
                    .then(reasonEmitter.emit(REASON_TYPE_WINNING,
                        builder -> builder
                            .author(userIdentity)
                            .subject(subject)
                            .attribute("activityTitle", activity.getSpec().getTitle())
                            .attribute("activityName", activity.getMetadata().getName())
                            .attribute("participantEmail", email)
                            .attribute("participantName", participantName)
                            .attribute("prizeName", prizeName)
                            .attribute("winTime", DATE_FORMATTER.format(
                                Objects.requireNonNullElse(spec.getWinTime(), spec.getParticipateTime())))
                    ));
            })
            .onErrorResume(e -> Mono.empty())
            .then();
    }

    @Override
    public Mono<Void> sendInstantNoPrizeNotification(LotteryParticipant participant, LotteryActivity activity) {
        return settingConfigGetter.getNotificationConfig()
            .filter(config -> Boolean.TRUE.equals(config.getEnableWinningNotification()))
            .flatMap(config -> {
                var spec = participant.getSpec();
                String email = spec.getEmail();
                if (email == null || email.isBlank()) {
                    return Mono.empty();
                }
                
                String participantName = Objects.requireNonNullElse(
                    spec.getDisplayName(), 
                    Objects.requireNonNullElse(spec.getUsername(), email)
                );

                var userIdentity = UserIdentity.anonymousWithEmail(email);
                
                var subscriber = new Subscription.Subscriber();
                subscriber.setName(userIdentity.name());
                
                var interestReason = new Subscription.InterestReason();
                interestReason.setReasonType(REASON_TYPE_INSTANT_NO_PRIZE);
                interestReason.setExpression("props.participantEmail == '" + email + "'");

                var subject = Subject.builder()
                    .apiVersion("lottery.xhhao.com/v1alpha1")
                    .kind("LotteryParticipant")
                    .name(participant.getMetadata().getName())
                    .title(activity.getSpec().getTitle())
                    .build();

                return notificationCenter.subscribe(subscriber, interestReason)
                    .then(reasonEmitter.emit(REASON_TYPE_INSTANT_NO_PRIZE,
                        builder -> builder
                            .author(userIdentity)
                            .subject(subject)
                            .attribute("activityTitle", activity.getSpec().getTitle())
                            .attribute("activityName", activity.getMetadata().getName())
                            .attribute("participantEmail", email)
                            .attribute("participantName", participantName)
                            .attribute("participateTime", DATE_FORMATTER.format(spec.getParticipateTime()))
                    ));
            })
            .onErrorResume(e -> Mono.empty())
            .then();
    }
}
