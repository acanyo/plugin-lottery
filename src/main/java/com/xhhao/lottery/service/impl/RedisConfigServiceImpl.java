package com.xhhao.lottery.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhhao.lottery.service.RedisConfigService;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
public class RedisConfigServiceImpl implements RedisConfigService {

    private static final String REDIS_CONFIG_MAP_NAME = "plugin-lottery-redis-configmap";
    private static final String LEGACY_CONFIG_MAP_NAME = "plugin-lottery-configmap";
    private static final String CONFIG_KEY = "redis-config";
    private static final String REDIS_GROUP = "redis";

    private final ReactiveExtensionClient client;
    private final ReactiveSettingFetcher settingFetcher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${spring.data.redis.host:}")
    private String haloRedisHost;

    @Value("${spring.data.redis.port:6379}")
    private Integer haloRedisPort;

    @Value("${spring.data.redis.database:0}")
    private Integer haloRedisDatabase;

    @Value("${spring.data.redis.password:}")
    private String haloRedisPassword;

    @Value("${halo.redis.enabled:false}")
    private Boolean haloRedisEnabled;

    @Value("${halo.session.store-type:in-memory}")
    private String haloSessionStoreType;

    private final Object cacheMonitor = new Object();
    private volatile CachedConnection cachedConnection;

    public RedisConfigServiceImpl(ReactiveExtensionClient client,
        ReactiveSettingFetcher settingFetcher) {
        this.client = client;
        this.settingFetcher = settingFetcher;
    }

    @Override
    public Mono<RedisConfigStatus> getStatus() {
        return loadPluginConfig()
            .flatMap(pluginConfig -> getEffectiveConfig(pluginConfig)
                .map(effectiveConfig -> buildStatus(pluginConfig, effectiveConfig)));
    }

    @Override
    public Mono<RedisConnectionTestResult> testConnection(PluginRedisConfig overrideConfig) {
        var normalizedConfig = normalizePluginConfig(overrideConfig);
        return getEffectiveConfig(normalizedConfig)
            .flatMap(optionalConfig -> optionalConfig
                .map(this::doTestConnection)
                .orElseGet(() -> {
                    var result = new RedisConnectionTestResult();
                    result.setSuccess(false);
                    result.setSource("NONE");
                    result.setMessage("未检测到可用的 Redis 配置。可填写插件配置，或使用 Halo 全局 Redis 配置。");
                    return Mono.just(result);
                }));
    }

    @Override
    public Mono<Optional<EffectiveRedisConfig>> getEffectiveConfig() {
        return loadPluginConfig().flatMap(this::getEffectiveConfig);
    }

    @Override
    public Mono<StatefulRedisConnection<String, String>> getRedisConnection() {
        return loadPluginConfig()
            .flatMap(pluginConfig -> getEffectiveConfig(pluginConfig)
                .flatMap(optionalConfig -> optionalConfig
                    .filter(config -> isVerificationValid(pluginConfig, config))
                    .map(config -> Mono.fromCallable(() -> getOrCreateConnection(config).connection()))
                    .orElseGet(Mono::empty)));
    }

    private Mono<PluginRedisConfig> loadPluginConfig() {
        return settingFetcher.fetch(REDIS_GROUP, PluginRedisConfig.class)
            .map(this::normalizePluginConfig)
            .switchIfEmpty(loadConfigFrom(REDIS_CONFIG_MAP_NAME))
            .switchIfEmpty(loadConfigFrom(LEGACY_CONFIG_MAP_NAME))
            .defaultIfEmpty(new PluginRedisConfig());
    }

    private Mono<PluginRedisConfig> loadConfigFrom(String configMapName) {
        return client.fetch(ConfigMap.class, configMapName)
            .flatMap(configMap -> Mono.justOrEmpty(configMap.getData())
                .flatMap(data -> Mono.justOrEmpty(data.get(CONFIG_KEY))))
            .flatMap(this::deserializePluginConfig);
    }

    private Mono<PluginRedisConfig> deserializePluginConfig(String rawConfig) {
        return Mono.fromCallable(() -> objectMapper.readValue(rawConfig, PluginRedisConfig.class))
            .onErrorResume(error -> {
                log.warn("Failed to deserialize plugin Redis config, using empty config.", error);
                return Mono.just(new PluginRedisConfig());
            });
    }

    private String serializePluginConfig(PluginRedisConfig config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化 Redis 配置失败", e);
        }
    }

    private Mono<Optional<EffectiveRedisConfig>> getEffectiveConfig(PluginRedisConfig pluginConfig) {
        if (pluginConfig.isConfigured()) {
            return Mono.just(Optional.of(toEffectiveConfig("PLUGIN", pluginConfig)));
        }

        var haloConfig = buildHaloConfig();
        if (Boolean.TRUE.equals(haloConfig.getConfigured())) {
            var effectiveConfig = new EffectiveRedisConfig();
            effectiveConfig.setSource("HALO");
            effectiveConfig.setHost(haloConfig.getHost());
            effectiveConfig.setPort(haloConfig.getPort());
            effectiveConfig.setDatabase(haloConfig.getDatabase());
            effectiveConfig.setPassword(Objects.requireNonNullElse(haloRedisPassword, ""));
            effectiveConfig.setPasswordConfigured(Boolean.TRUE.equals(haloConfig.getPasswordConfigured()));
            return Mono.just(Optional.of(effectiveConfig));
        }

        return Mono.just(Optional.empty());
    }

    private EffectiveRedisConfig toEffectiveConfig(String source, PluginRedisConfig config) {
        var effectiveConfig = new EffectiveRedisConfig();
        effectiveConfig.setSource(source);
        effectiveConfig.setHost(config.getHost());
        effectiveConfig.setPort(Objects.requireNonNullElse(config.getPort(), 6379));
        effectiveConfig.setDatabase(Objects.requireNonNullElse(config.getDatabase(), 0));
        effectiveConfig.setPassword(Objects.requireNonNullElse(config.getPassword(), ""));
        effectiveConfig.setPasswordConfigured(StringUtils.hasText(config.getPassword()));
        return effectiveConfig;
    }

    private RedisConfigStatus buildStatus(
        PluginRedisConfig pluginConfig,
        Optional<EffectiveRedisConfig> effectiveConfig
    ) {
        var status = new RedisConfigStatus();
        status.setPluginConfig(pluginConfig);
        status.setHaloConfig(buildHaloConfig());

        effectiveConfig.ifPresent(status::setEffectiveConfig);
        status.setEffectiveSource(effectiveConfig.map(EffectiveRedisConfig::getSource).orElse("NONE"));
        boolean verified = effectiveConfig
            .map(config -> isVerificationValid(pluginConfig, config))
            .orElse(false);
        status.setConnectionVerified(verified);
        status.setInstantLotteryAvailable(verified);
        status.setMessage(resolveStatusMessage(status.getEffectiveSource(), effectiveConfig.isPresent(), verified));
        return status;
    }

    private HaloRedisConfig buildHaloConfig() {
        var haloConfig = new HaloRedisConfig();
        haloConfig.setEnabled(Boolean.TRUE.equals(haloRedisEnabled));
        haloConfig.setSessionStoreType(Objects.requireNonNullElse(haloSessionStoreType, "in-memory"));
        haloConfig.setHost(haloRedisHost);
        haloConfig.setPort(Objects.requireNonNullElse(haloRedisPort, 6379));
        haloConfig.setDatabase(Objects.requireNonNullElse(haloRedisDatabase, 0));
        haloConfig.setPasswordConfigured(StringUtils.hasText(haloRedisPassword));
        haloConfig.setConfigured(StringUtils.hasText(haloRedisHost));
        return haloConfig;
    }

    private CachedConnection getOrCreateConnection(EffectiveRedisConfig config) {
        var current = cachedConnection;
        if (current != null && current.matches(config)) {
            return current;
        }

        synchronized (cacheMonitor) {
            current = cachedConnection;
            if (current != null && current.matches(config)) {
                return current;
            }

            if (current != null) {
                current.destroy();
            }

            var redisClient = RedisClient.create(buildRedisUri(config));
            redisClient.setDefaultTimeout(Duration.ofSeconds(3));
            var connection = redisClient.connect();
            cachedConnection = new CachedConnection(config, redisClient, connection);
            return cachedConnection;
        }
    }

    private Mono<Void> invalidateCache() {
        return Mono.fromRunnable(() -> {
            synchronized (cacheMonitor) {
                if (cachedConnection != null) {
                    cachedConnection.destroy();
                    cachedConnection = null;
                }
            }
        });
    }

    private Mono<RedisConnectionTestResult> doTestConnection(EffectiveRedisConfig config) {
        return Mono.defer(() -> {
            long start = System.currentTimeMillis();
            var redisClient = RedisClient.create(buildRedisUri(config));
            redisClient.setDefaultTimeout(Duration.ofSeconds(3));
            StatefulRedisConnection<String, String> connection = null;

            try {
                connection = redisClient.connect();
                var finalConnection = connection;
                return Mono.fromFuture(finalConnection.async().ping().toCompletableFuture())
                    .timeout(Duration.ofSeconds(3))
                    .map(pong -> {
                        var result = new RedisConnectionTestResult();
                        var success = "PONG".equalsIgnoreCase(pong);
                        result.setSuccess(success);
                        result.setSource(config.getSource());
                        result.setLatencyMs(System.currentTimeMillis() - start);
                        result.setVerificationSignature(success
                            ? buildVerificationSignature(config)
                            : null);
                        result.setMessage(result.getSuccess()
                            ? "Redis 连接测试成功。"
                            : "Redis 返回结果异常: " + pong);
                        return result;
                    })
                    .onErrorResume(error -> {
                        log.warn("Failed to test Redis connection.", error);
                        var result = new RedisConnectionTestResult();
                        result.setSuccess(false);
                        result.setSource(config.getSource());
                        result.setLatencyMs(System.currentTimeMillis() - start);
                        result.setMessage("Redis 连接测试失败: " + error.getMessage());
                        return Mono.just(result);
                    })
                    .doFinally(signalType -> {
                        finalConnection.close();
                        redisClient.shutdown();
                    });
            } catch (Exception error) {
                if (connection != null) {
                    connection.close();
                }
                redisClient.shutdown();
                log.warn("Failed to open Redis connection.", error);
                var result = new RedisConnectionTestResult();
                result.setSuccess(false);
                result.setSource(config.getSource());
                result.setLatencyMs(System.currentTimeMillis() - start);
                result.setMessage("Redis 连接测试失败: " + error.getMessage());
                return Mono.just(result);
            }
        });
    }

    private RedisURI buildRedisUri(EffectiveRedisConfig config) {
        var builder = RedisURI.builder()
            .withHost(config.getHost())
            .withPort(Objects.requireNonNullElse(config.getPort(), 6379))
            .withDatabase(Objects.requireNonNullElse(config.getDatabase(), 0))
            .withTimeout(Duration.ofSeconds(3));
        if (StringUtils.hasText(config.getPassword())) {
            builder.withPassword(config.getPassword().toCharArray());
        }
        return builder.build();
    }

    private PluginRedisConfig normalizePluginConfig(PluginRedisConfig config) {
        var normalized = new PluginRedisConfig();
        if (config == null) {
            return normalized;
        }

        normalized.setHost(trimToNull(config.getHost()));
        normalized.setPort(config.getPort());
        normalized.setDatabase(config.getDatabase());
        normalized.setPassword(trimToNull(config.getPassword()));
        normalized.setConnectionVerified(Boolean.TRUE.equals(config.getConnectionVerified()));
        normalized.setVerifiedSource(trimToNull(config.getVerifiedSource()));
        normalized.setVerifiedSignature(trimToNull(config.getVerifiedSignature()));
        normalized.setVerifiedAt(config.getVerifiedAt());
        return normalized;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private record CachedConnection(
        EffectiveRedisConfig config,
        RedisClient client,
        StatefulRedisConnection<String, String> connection
    ) {
        boolean matches(EffectiveRedisConfig candidate) {
            return Objects.equals(config.getSource(), candidate.getSource())
                && Objects.equals(config.getHost(), candidate.getHost())
                && Objects.equals(config.getPort(), candidate.getPort())
                && Objects.equals(config.getDatabase(), candidate.getDatabase())
                && Objects.equals(config.getPassword(), candidate.getPassword());
        }

        void destroy() {
            connection.close();
            client.shutdown();
        }
    }

    private boolean isVerificationValid(PluginRedisConfig pluginConfig, EffectiveRedisConfig effectiveConfig) {
        return Boolean.TRUE.equals(pluginConfig.getConnectionVerified())
            && Objects.equals(pluginConfig.getVerifiedSource(), effectiveConfig.getSource())
            && Objects.equals(pluginConfig.getVerifiedSignature(), buildVerificationSignature(effectiveConfig));
    }

    private String resolveStatusMessage(String effectiveSource, boolean configured, boolean verified) {
        if (!configured) {
            return "当前未检测到 Redis 配置。建议改用定时开奖，或先配置 Redis。";
        }
        if (verified) {
            return switch (effectiveSource) {
                case "PLUGIN" -> "Redis 连接测试已通过，当前使用插件 Redis 配置，即时开奖可启用。";
                case "HALO" -> "Redis 连接测试已通过，当前复用 Halo 全局 Redis 配置，即时开奖可启用。";
                default -> "Redis 连接测试已通过，即时开奖可启用。";
            };
        }
        return switch (effectiveSource) {
            case "PLUGIN" -> "已检测到插件 Redis 配置，但尚未通过连接测试。请先点击“测试连接”，即时开奖当前禁用。";
            case "HALO" -> "已检测到 Halo 全局 Redis 配置，但尚未通过连接测试。请先点击“测试连接”，即时开奖当前禁用。";
            default -> "Redis 尚未通过连接测试，即时开奖当前禁用。";
        };
    }

    private String buildVerificationSignature(EffectiveRedisConfig config) {
        var raw = String.join("|",
            Objects.requireNonNullElse(config.getSource(), ""),
            Objects.requireNonNullElse(config.getHost(), ""),
            Integer.toString(Objects.requireNonNullElse(config.getPort(), 6379)),
            Integer.toString(Objects.requireNonNullElse(config.getDatabase(), 0)),
            Objects.requireNonNullElse(config.getPassword(), "")
        );
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Failed to build Redis verification signature", e);
        }
    }
}
