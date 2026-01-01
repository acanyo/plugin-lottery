package com.xhhao.lottery.endpoint;

import com.xhhao.lottery.entity.LotteryActivity;
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

import java.time.Instant;
import java.util.List;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;

import io.swagger.v3.oas.annotations.enums.ParameterIn;

/**
 * 公开接口（匿名可访问）
 */
@Component
@RequiredArgsConstructor
public class LotteryPublicEndpoint implements CustomEndpoint {

    private final LotteryService lotteryService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        var tag = "LotteryV1alpha1Public";
        return SpringdocRouteBuilder.route()
            .GET("/lotteries/{name}", this::getActivity,
                builder -> builder.operationId("GetLotteryActivity")
                    .tag(tag).description("获取活动详情")
                    .parameter(parameterBuilder().name("name").in(ParameterIn.PATH).required(true)))
            .POST("/lotteries/{name}/participate", this::participate,
                builder -> builder.operationId("ParticipateLottery")
                    .tag(tag).description("匿名参与抽奖")
                    .parameter(parameterBuilder().name("name").in(ParameterIn.PATH).required(true))
                    .requestBody(requestBodyBuilder().implementation(ParticipateRequest.class)))
            .GET("/lotteries/{name}/status", this::getStatus,
                builder -> builder.operationId("GetLotteryStatus")
                    .tag(tag).description("查询参与状态")
                    .parameter(parameterBuilder().name("name").in(ParameterIn.PATH).required(true))
                    .parameter(parameterBuilder().name("token").in(ParameterIn.QUERY).required(false)))
            .POST("/lotteries/{name}/recover", this::recover,
                builder -> builder.operationId("RecoverLotteryToken")
                    .tag(tag).description("通过邮箱找回token")
                    .parameter(parameterBuilder().name("name").in(ParameterIn.PATH).required(true))
                    .requestBody(requestBodyBuilder().implementation(RecoverRequest.class)))
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.lottery.xhhao.com/v1alpha1");
    }

    private Mono<ServerResponse> getActivity(ServerRequest request) {
        String name = request.pathVariable("name");
        return lotteryService.getActivity(name)
            .flatMap(activity -> {
                var resp = new ActivityResponse();
                resp.setName(activity.getMetadata().getName());
                resp.setTitle(activity.getSpec().getTitle());
                resp.setDescription(activity.getSpec().getDescription());
                resp.setLotteryType(activity.getSpec().getLotteryType());
                resp.setParticipationType(activity.getSpec().getParticipationType());
                resp.setStartTime(activity.getSpec().getStartTime());
                resp.setEndTime(activity.getSpec().getEndTime());
                resp.setMaxParticipants(activity.getSpec().getMaxParticipants());
                resp.setPrizes(activity.getSpec().getPrizes());
                resp.setThankYouSlots(activity.getSpec().getThankYouSlots());
                if (activity.getStatus() != null) {
                    resp.setState(activity.getStatus().getState());
                    resp.setParticipantCount(activity.getStatus().getParticipantCount());
                    if (activity.getStatus().getState() == LotteryActivity.State.DRAWN) {
                        resp.setWinners(activity.getStatus().getWinners());
                    }
                }
                return ServerResponse.ok().bodyValue(resp);
            })
            .switchIfEmpty(ServerResponse.notFound().build());
    }

    private Mono<ServerResponse> participate(ServerRequest request) {
        String name = request.pathVariable("name");
        String ip = getClientIp(request);
        return request.bodyToMono(ParticipateRequest.class)
            .flatMap(body -> lotteryService.participateAnonymous(name, body.getEmail(), body.getDisplayName(), ip))
            .flatMap(p -> {
                var resp = new ParticipateResponse();
                resp.setSuccess(true);
                resp.setMessage("参与成功");
                resp.setToken(p.getSpec().getToken());
                // 即时开奖结果
                resp.setIsWinner(p.getSpec().getIsWinner());
                resp.setPrizeName(p.getSpec().getPrizeName());
                return ServerResponse.ok().bodyValue(resp);
            })
            .onErrorResume(e -> {
                var resp = new ParticipateResponse();
                resp.setSuccess(false);
                resp.setMessage(e.getMessage());
                return ServerResponse.ok().bodyValue(resp);
            });
    }

    private Mono<ServerResponse> getStatus(ServerRequest request) {
        String name = request.pathVariable("name");
        String token = request.queryParam("token").orElse(null);
        if (token == null || token.isBlank()) {
            var resp = new StatusResponse();
            resp.setParticipated(false);
            return ServerResponse.ok().bodyValue(resp);
        }
        return lotteryService.findByToken(token)
            .filter(p -> name.equals(p.getSpec().getActivityName()))
            .flatMap(p -> lotteryService.getWinnerByToken(name, token)
                .map(w -> {
                    var resp = new StatusResponse();
                    resp.setParticipated(true);
                    resp.setToken(token);
                    resp.setIsWinner(true);
                    resp.setPrizeName(w.getPrizeName());
                    return resp;
                })
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    var resp = new StatusResponse();
                    resp.setParticipated(true);
                    resp.setToken(token);
                    resp.setIsWinner(false);
                    return resp;
                })))
            .flatMap(resp -> ServerResponse.ok().bodyValue(resp))
            .switchIfEmpty(Mono.defer(() -> {
                var resp = new StatusResponse();
                resp.setParticipated(false);
                return ServerResponse.ok().bodyValue(resp);
            }));
    }

    private Mono<ServerResponse> recover(ServerRequest request) {
        String name = request.pathVariable("name");
        return request.bodyToMono(RecoverRequest.class)
            .flatMap(body -> {
                if (body.getEmail() == null || body.getEmail().isBlank()) {
                    return Mono.error(new IllegalArgumentException("邮箱不能为空"));
                }
                return lotteryService.findByActivityAndEmail(name, body.getEmail());
            })
            .flatMap(p -> {
                var resp = new ParticipateResponse();
                resp.setSuccess(true);
                resp.setMessage("找回成功");
                resp.setToken(p.getSpec().getToken());
                return ServerResponse.ok().bodyValue(resp);
            })
            .switchIfEmpty(Mono.defer(() -> {
                var resp = new ParticipateResponse();
                resp.setSuccess(false);
                resp.setMessage("未找到参与记录");
                return ServerResponse.ok().bodyValue(resp);
            }))
            .onErrorResume(e -> {
                var resp = new ParticipateResponse();
                resp.setSuccess(false);
                resp.setMessage(e.getMessage());
                return ServerResponse.ok().bodyValue(resp);
            });
    }

    private String getClientIp(ServerRequest request) {
        String xff = request.headers().firstHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.remoteAddress().map(a -> a.getAddress().getHostAddress()).orElse("");
    }

    @Data
    public static class ParticipateRequest {
        private String email;
        private String displayName;
    }

    @Data
    public static class RecoverRequest {
        private String email;
    }

    @Data
    public static class ParticipateResponse {
        private Boolean success;
        private String message;
        private String token;
        // 即时开奖结果
        private Boolean isWinner;
        private String prizeName;
    }

    @Data
    public static class StatusResponse {
        private Boolean participated;
        private String token;
        private Boolean isWinner;
        private String prizeName;
    }

    @Data
    public static class ActivityResponse {
        private String name;
        private String title;
        private String description;
        private LotteryActivity.LotteryType lotteryType;
        private LotteryActivity.ParticipationType participationType;
        private Instant startTime;
        private Instant endTime;
        private Integer maxParticipants;
        private LotteryActivity.State state;
        private Integer participantCount;
        private List<LotteryActivity.Prize> prizes;
        private List<LotteryActivity.Winner> winners;
        /** 谢谢参与格子数量（大转盘/刮刮乐使用） */
        private Integer thankYouSlots;
    }
}
