package com.xhhao.lottery.endpoint;

import com.xhhao.lottery.entity.LotteryParticipant;
import com.xhhao.lottery.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.ReactiveExtensionClient;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;

@Component
@RequiredArgsConstructor
public class LotteryUcEndpoint implements CustomEndpoint {

    private final ReactiveExtensionClient client;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        var tag = "LotteryV1alpha1Uc";
        return SpringdocRouteBuilder.route()
            .GET("/winnings", this::listMyWinnings,
                builder -> builder.operationId("ListMyWinnings")
                    .tag(tag)
                    .description("查询当前用户的中奖记录")
                    .parameter(parameterBuilder().name("page").description("页码").required(false))
                    .parameter(parameterBuilder().name("size").description("每页数量").required(false))
                    .response(responseBuilder().implementation(ListResult.class)))
            .GET("/participations", this::listMyParticipations,
                builder -> builder.operationId("ListMyParticipations")
                    .tag(tag)
                    .description("查询当前用户的参与记录")
                    .parameter(parameterBuilder().name("page").description("页码").required(false))
                    .parameter(parameterBuilder().name("size").description("每页数量").required(false))
                    .response(responseBuilder().implementation(ListResult.class)))
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("uc.api.lottery.xhhao.com/v1alpha1");
    }

    private Mono<ServerResponse> listMyWinnings(ServerRequest request) {
        int page = request.queryParam("page").map(Integer::parseInt).orElse(1);
        int size = request.queryParam("size").map(Integer::parseInt).orElse(20);
        
        return getCurrentUserIdentifiers()
            .flatMap(identifiers -> {
                return client.list(LotteryParticipant.class, participant -> {
                        var spec = participant.getSpec();
                        if (spec == null || !Boolean.TRUE.equals(spec.getIsWinner())) {
                            return false;
                        }
                        return identifiers.username().equals(spec.getUsername()) 
                            || (spec.getEmail() != null && identifiers.email().equalsIgnoreCase(spec.getEmail()));
                    }, null, page, size);
            })
            .flatMap(result -> ServerResponse.ok().bodyValue(result))
            .switchIfEmpty(ServerResponse.ok().bodyValue(ListResult.emptyResult()));
    }

    private Mono<ServerResponse> listMyParticipations(ServerRequest request) {
        int page = request.queryParam("page").map(Integer::parseInt).orElse(1);
        int size = request.queryParam("size").map(Integer::parseInt).orElse(20);
        
        return getCurrentUserIdentifiers()
            .flatMap(identifiers -> {
                return client.list(LotteryParticipant.class, participant -> {
                        var spec = participant.getSpec();
                        if (spec == null) {
                            return false;
                        }
                        return identifiers.username().equals(spec.getUsername()) 
                            || (spec.getEmail() != null && identifiers.email().equalsIgnoreCase(spec.getEmail()));
                    }, null, page, size);
            })
            .flatMap(result -> ServerResponse.ok().bodyValue(result))
            .switchIfEmpty(ServerResponse.ok().bodyValue(ListResult.emptyResult()));
    }

    private Mono<UserIdentifiers> getCurrentUserIdentifiers() {
        return ReactiveSecurityContextHolder.getContext()
            .flatMap(securityContext -> {
                Authentication authentication = securityContext.getAuthentication();
                if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
                    return Mono.empty();
                }
                String username = authentication.getName();
                return SecurityUtil.getCurrentUser(client)
                    .map(user -> new UserIdentifiers(
                        username,
                        user.getSpec().getEmail() != null ? user.getSpec().getEmail() : ""
                    ));
            });
    }

    private record UserIdentifiers(String username, String email) {}
}
