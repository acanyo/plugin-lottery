package com.xhhao.lottery.endpoint;

import com.xhhao.lottery.service.RedisConfigService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;

@Component
@RequiredArgsConstructor
public class LotteryRedisConfigEndpoint implements CustomEndpoint {

    private final RedisConfigService redisConfigService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        var tag = "LotteryV1alpha1Console";
        return SpringdocRouteBuilder.route()
            .GET("/redis-config", this::getRedisConfig,
                builder -> builder.operationId("GetLotteryRedisConfig")
                    .tag(tag)
                    .description("获取抽奖插件 Redis 配置与当前生效来源")
                    .response(responseBuilder().implementation(RedisConfigService.RedisConfigStatus.class)))
            .POST("/redis-config/test", this::testRedisConfig,
                builder -> builder.operationId("TestLotteryRedisConfig")
                    .tag(tag)
                    .description("测试抽奖插件当前表单或当前生效的 Redis 配置")
                    .requestBody(requestBodyBuilder().implementation(RedisConfigRequest.class))
                    .response(responseBuilder().implementation(RedisConfigService.RedisConnectionTestResult.class)))
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("console.api.lottery.xhhao.com/v1alpha1");
    }

    private Mono<ServerResponse> getRedisConfig(ServerRequest request) {
        return redisConfigService.getStatus()
            .flatMap(response -> ServerResponse.ok().bodyValue(response));
    }

    private Mono<ServerResponse> testRedisConfig(ServerRequest request) {
        return request.bodyToMono(RedisConfigRequest.class)
            .defaultIfEmpty(new RedisConfigRequest())
            .flatMap(body -> redisConfigService.testConnection(body.toPluginConfig()))
            .flatMap(response -> ServerResponse.ok().bodyValue(response));
    }

    @Data
    public static class RedisConfigRequest {
        private String host;
        private Integer port;
        private Integer database;
        private String password;

        RedisConfigService.PluginRedisConfig toPluginConfig() {
            var config = new RedisConfigService.PluginRedisConfig();
            config.setHost(host);
            config.setPort(port);
            config.setDatabase(database);
            config.setPassword(password);
            return config;
        }
    }
}
