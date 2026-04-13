package com.xhhao.lottery.service;

import lombok.Data;
import io.lettuce.core.api.StatefulRedisConnection;
import reactor.core.publisher.Mono;

import java.util.Optional;

public interface RedisConfigService {

    Mono<RedisConfigStatus> getStatus();

    Mono<RedisConnectionTestResult> testConnection(PluginRedisConfig overrideConfig);

    Mono<Optional<EffectiveRedisConfig>> getEffectiveConfig();

    Mono<StatefulRedisConnection<String, String>> getRedisConnection();

    @Data
    class PluginRedisConfig {
        private String host;
        private Integer port;
        private Integer database;
        private String password;
        private Boolean connectionVerified;
        private String verifiedSource;
        private String verifiedSignature;
        private Long verifiedAt;

        public boolean isConfigured() {
            return host != null && !host.isBlank();
        }
    }

    @Data
    class HaloRedisConfig {
        private Boolean enabled;
        private String sessionStoreType;
        private String host;
        private Integer port;
        private Integer database;
        private Boolean passwordConfigured;
        private Boolean configured;
    }

    @Data
    class EffectiveRedisConfig {
        private String source;
        private String host;
        private Integer port;
        private Integer database;
        private String password;
        private Boolean passwordConfigured;
    }

    @Data
    class RedisConfigStatus {
        private PluginRedisConfig pluginConfig;
        private HaloRedisConfig haloConfig;
        private EffectiveRedisConfig effectiveConfig;
        private String effectiveSource;
        private Boolean connectionVerified;
        private Boolean instantLotteryAvailable;
        private String message;
    }

    @Data
    class RedisConnectionTestResult {
        private Boolean success;
        private String source;
        private String message;
        private Long latencyMs;
        private String verificationSignature;
    }
}
