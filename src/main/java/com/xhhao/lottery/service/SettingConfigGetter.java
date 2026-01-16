package com.xhhao.lottery.service;

import lombok.Data;
import reactor.core.publisher.Mono;

public interface SettingConfigGetter {
    
    Mono<NotificationConfig> getNotificationConfig();
    
    Mono<VerificationConfig> getVerificationConfig();

    @Data
    class NotificationConfig {
        public static final String GROUP = "notification";
        
        private Boolean enableParticipateNotification = false;
        private Boolean enableWinningNotification = true;
    }
    
    @Data
    class VerificationConfig {
        public static final String GROUP = "verification";
        
        private Boolean enableEmailVerification = true;
        private Integer verificationCodeExpireMinutes = 5;
        private Integer verificationCodeIntervalSeconds = 60;
    }
}
