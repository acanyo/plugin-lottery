package com.xhhao.lottery.endpoint;

import com.xhhao.lottery.entity.LotteryParticipant;
import com.xhhao.lottery.query.LotteryActivityQuery;
import com.xhhao.lottery.service.LotteryService;
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
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static run.halo.app.extension.index.query.Queries.equal;

import io.swagger.v3.oas.annotations.enums.ParameterIn;

@Component
@RequiredArgsConstructor
public class LotteryEndpoint implements CustomEndpoint {

    private final LotteryService lotteryService;
    private final ReactiveExtensionClient client;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        var tag = "LotteryV1alpha1Console";
        return SpringdocRouteBuilder.route()
            .GET("/lotteries", this::listLotteries,
                builder -> {
                    builder.operationId("ListLotteries")
                        .tag(tag)
                        .description("查询抽奖活动列表")
                        .response(responseBuilder().implementation(ListResult.class));
                    LotteryActivityQuery.buildParameters(builder);
                })
            .GET("/lotteries/{name}/participants", this::listParticipants,
                builder -> builder.operationId("ListParticipants")
                    .tag(tag)
                    .description("查询活动参与者列表")
                    .parameter(parameterBuilder().name("name").in(ParameterIn.PATH).required(true))
                    .parameter(parameterBuilder().name("page").description("页码").required(false))
                    .parameter(parameterBuilder().name("size").description("每页数量").required(false))
                    .response(responseBuilder().implementation(ListResult.class)))
            .POST("/draw", this::draw,
                builder -> builder.operationId("DrawLottery")
                    .tag(tag)
                    .description("手动开奖")
                    .parameter(parameterBuilder().name("name").description("活动名称").required(true))
                    .response(responseBuilder().implementation(DrawResponse.class)))
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("console.api.lottery.xhhao.com/v1alpha1");
    }

    private Mono<ServerResponse> listLotteries(ServerRequest request) {
        var query = new LotteryActivityQuery(request);
        return lotteryService.listActivities(query)
            .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    private Mono<ServerResponse> listParticipants(ServerRequest request) {
        String activityName = request.pathVariable("name");
        int page = request.queryParam("page").map(Integer::parseInt).orElse(1);
        int size = request.queryParam("size").map(Integer::parseInt).orElse(20);
        
        return client.listBy(LotteryParticipant.class,
                ListOptions.builder()
                    .fieldQuery(equal("spec.activityName", activityName))
                    .build(),
                PageRequestImpl.of(page, size))
            .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    private Mono<ServerResponse> draw(ServerRequest request) {
        String activityName = request.queryParam("name").orElse(null);
        if (activityName == null || activityName.isBlank()) {
            var response = new DrawResponse();
            response.setSuccess(false);
            response.setMessage("活动名称不能为空");
            return ServerResponse.badRequest().bodyValue(response);
        }
        
        return lotteryService.draw(activityName)
            .flatMap(activity -> {
                var response = new DrawResponse();
                response.setSuccess(true);
                response.setMessage("开奖成功");
                response.setWinnerCount(activity.getStatus().getWinners() != null 
                    ? activity.getStatus().getWinners().size() : 0);
                return ServerResponse.ok().bodyValue(response);
            })
            .onErrorResume(e -> {
                var response = new DrawResponse();
                response.setSuccess(false);
                response.setMessage(e.getMessage());
                return ServerResponse.ok().bodyValue(response);
            });
    }

    @Data
    public static class DrawResponse {
        private Boolean success;
        private String message;
        private Integer winnerCount;
    }
}
