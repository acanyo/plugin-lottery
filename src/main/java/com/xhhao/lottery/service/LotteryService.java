package com.xhhao.lottery.service;

import com.xhhao.lottery.entity.LotteryActivity;
import com.xhhao.lottery.entity.LotteryParticipant;
import com.xhhao.lottery.query.LotteryActivityQuery;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListResult;

/**
 * 抽奖服务接口
 */
public interface LotteryService {

    /**
     * 获取活动详情（会自动检查状态和定时开奖）
     */
    Mono<LotteryActivity> getActivity(String activityName);

    /**
     * 分页查询活动列表（会自动检查状态）
     */
    Mono<ListResult<LotteryActivity>> listActivities(LotteryActivityQuery query);

    /**
     * 执行开奖
     */
    Mono<LotteryActivity> draw(String activityName);

    /**
     * 匿名参与抽奖
     * 
     * @param activityName 活动名称
     * @param email 邮箱（必填）
     * @param displayName 显示名称（可选）
     * @param ipAddress IP 地址
     * @return 参与记录（即时开奖类型会包含中奖信息）
     */
    Mono<LotteryParticipant> participateAnonymous(String activityName, String email, 
                                                   String displayName, String ipAddress);

    /**
     * 根据 token 查询参与记录
     */
    Mono<LotteryParticipant> findByToken(String token);

    /**
     * 根据邮箱和活动查询参与记录（用于找回 token）
     */
    Mono<LotteryParticipant> findByActivityAndEmail(String activityName, String email);

    /**
     * 查询中奖结果
     */
    Mono<LotteryActivity.Winner> getWinnerByToken(String activityName, String token);

    /**
     * 生成 token
     */
    String generateToken(String activityName, String identifier);
}
