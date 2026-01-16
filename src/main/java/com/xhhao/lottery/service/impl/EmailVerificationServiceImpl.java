package com.xhhao.lottery.service.impl;

import com.xhhao.lottery.service.EmailVerificationService;
import com.xhhao.lottery.service.SettingConfigGetter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.notification.Reason.Subject;
import run.halo.app.core.extension.notification.Subscription;
import run.halo.app.notification.NotificationCenter;
import run.halo.app.notification.NotificationReasonEmitter;
import run.halo.app.notification.UserIdentity;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationServiceImpl implements EmailVerificationService {

    private final SettingConfigGetter settingConfigGetter;
    private final NotificationReasonEmitter reasonEmitter;
    private final NotificationCenter notificationCenter;

    private final Map<String, CodeEntry> codeStore = new ConcurrentHashMap<>();
    private final Map<String, Instant> sendTimeStore = new ConcurrentHashMap<>();

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String REASON_TYPE = "lottery-verification-code";

    @Override
    public Mono<SendCodeResult> sendCode(String email, String activityName) {
        return settingConfigGetter.getVerificationConfig()
            .flatMap(config -> {
                if (!Boolean.TRUE.equals(config.getEnableEmailVerification())) {
                    return Mono.just(new SendCodeResult(false, "邮箱验证未启用"));
                }

                int intervalSeconds = config.getVerificationCodeIntervalSeconds() != null 
                    ? config.getVerificationCodeIntervalSeconds() : 60;

                var sendTimeKey = buildKey(email, activityName);
                Instant lastSend = sendTimeStore.get(sendTimeKey);
                if (lastSend != null) {
                    long elapsed = Instant.now().getEpochSecond() - lastSend.getEpochSecond();
                    if (elapsed < intervalSeconds) {
                        long remaining = intervalSeconds - elapsed;
                        return Mono.just(new SendCodeResult(false, 
                            "请 " + remaining + " 秒后再试"));
                    }
                }

                var code = String.format("%06d", RANDOM.nextInt(1000000));
                int expireMinutes = config.getVerificationCodeExpireMinutes() != null 
                    ? config.getVerificationCodeExpireMinutes() : 5;

                var key = buildKey(email, activityName);
                codeStore.put(key, new CodeEntry(code, 
                    Instant.now().plusSeconds(expireMinutes * 60L)));
                sendTimeStore.put(sendTimeKey, Instant.now());

                return sendVerificationEmail(email, code, expireMinutes)
                    .thenReturn(new SendCodeResult(true, "验证码已发送"))
                    .onErrorResume(e -> {
                        log.error("发送验证码邮件失败: {}", e.getMessage());
                        return Mono.just(new SendCodeResult(false, "发送失败，请稍后重试"));
                    });
            });
    }

    @Override
    public Mono<Boolean> verifyCode(String email, String activityName, String code) {
        if (code == null || code.isBlank()) {
            return Mono.just(false);
        }

        var key = buildKey(email, activityName);
        var entry = codeStore.get(key);

        if (entry == null) {
            return Mono.just(false);
        }

        if (Instant.now().isAfter(entry.expireTime())) {
            codeStore.remove(key);
            return Mono.just(false);
        }

        if (entry.code().equals(code)) {
            codeStore.remove(key);
            return Mono.just(true);
        }

        return Mono.just(false);
    }

    @Override
    public Mono<Boolean> isVerificationEnabled() {
        return settingConfigGetter.getVerificationConfig()
            .map(config -> Boolean.TRUE.equals(config.getEnableEmailVerification()));
    }

    private Mono<Void> sendVerificationEmail(String email, String code, int expireMinutes) {
        var userIdentity = UserIdentity.anonymousWithEmail(email);
        var subscriber = new Subscription.Subscriber();
        subscriber.setName(userIdentity.name());
        
        var interestReason = new Subscription.InterestReason();
        interestReason.setReasonType(REASON_TYPE);
        interestReason.setExpression("props.email == '" + email + "'");
        
        var subject = Subject.builder()
            .apiVersion("lottery.xhhao.com/v1alpha1")
            .kind("VerificationCode")
            .name("code-" + System.currentTimeMillis())
            .title("抽奖验证码")
            .build();

        return notificationCenter.subscribe(subscriber, interestReason)
            .then(reasonEmitter.emit(REASON_TYPE,
                builder -> builder
                    .author(userIdentity)
                    .subject(subject)
                    .attribute("email", email)
                    .attribute("code", code)
                    .attribute("expireMinutes", String.valueOf(expireMinutes))
            ))
            .onErrorResume(e -> Mono.empty());
    }

    private String buildKey(String email, String activityName) {
        return email.toLowerCase() + ":" + activityName;
    }

    private record CodeEntry(String code, Instant expireTime) {}
}
