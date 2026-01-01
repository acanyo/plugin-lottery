package com.xhhao.lottery.query;

import io.swagger.v3.oas.annotations.enums.ParameterIn;
import org.apache.commons.lang3.StringUtils;
import org.springdoc.core.fn.builders.operation.Builder;
import org.springframework.lang.Nullable;
import org.springframework.web.reactive.function.server.ServerRequest;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.router.IListRequest;
import run.halo.app.extension.router.SortableRequest;

import java.util.Optional;

import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static run.halo.app.extension.index.query.Queries.*;
import static run.halo.app.extension.router.QueryParamBuildUtil.sortParameter;

public class LotteryActivityQuery extends SortableRequest {

    public LotteryActivityQuery(ServerRequest request) {
        super(request.exchange());
    }

    @Nullable
    public String getKeyword() {
        return queryParams.getFirst("keyword");
    }

    @Nullable
    public String getState() {
        return queryParams.getFirst("state");
    }

    @Nullable
    public String getParticipationType() {
        return queryParams.getFirst("participationType");
    }

    @Override
    public ListOptions toListOptions() {
        var builder = ListOptions.builder(super.toListOptions());

        Optional.ofNullable(getKeyword())
            .filter(StringUtils::isNotBlank)
            .ifPresent(keyword -> builder.andQuery(or(
                contains("spec.title", keyword),
                contains("spec.description", keyword),
                equal("metadata.name", keyword)
            )));

        Optional.ofNullable(getState())
            .filter(StringUtils::isNotBlank)
            .ifPresent(state -> builder.andQuery(equal("status.state", state)));

        Optional.ofNullable(getParticipationType())
            .filter(StringUtils::isNotBlank)
            .ifPresent(type -> builder.andQuery(equal("spec.participationType", type)));

        return builder.build();
    }

    public static void buildParameters(Builder builder) {
        IListRequest.buildParameters(builder);
        builder.parameter(sortParameter())
            .parameter(parameterBuilder()
                .in(ParameterIn.QUERY)
                .name("keyword")
                .description("按标题/描述搜索")
                .implementation(String.class)
                .required(false))
            .parameter(parameterBuilder()
                .in(ParameterIn.QUERY)
                .name("state")
                .description("状态筛选: PENDING, RUNNING, ENDED, DRAWN")
                .implementation(String.class)
                .required(false))
            .parameter(parameterBuilder()
                .in(ParameterIn.QUERY)
                .name("participationType")
                .description("参与类型: NONE, LOGIN, COMMENT, LOGIN_AND_COMMENT")
                .implementation(String.class)
                .required(false));
    }
}
