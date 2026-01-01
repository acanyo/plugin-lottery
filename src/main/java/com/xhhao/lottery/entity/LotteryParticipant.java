package com.xhhao.lottery.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.time.Instant;

/**
 * 抽奖参与记录
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "lottery.xhhao.com", version = "v1alpha1", kind = "LotteryParticipant",
        plural = "lotteryparticipants", singular = "lotteryparticipant")
public class LotteryParticipant extends AbstractExtension {

    private LotteryParticipantSpec spec;

    @Data
    public static class LotteryParticipantSpec {
        /**
         * 关联的活动名称
         */
        private String activityName;

        /**
         * 邮箱（核心标识，所有模式都用邮箱）
         */
        private String email;

        /**
         * 显示名称
         */
        private String displayName;

        /**
         * 登录用户名（LOGIN 模式记录，可选）
         */
        private String username;

        /**
         * 关联的评论名称（COMMENT 模式记录）
         */
        private String commentName;

        /**
         * 校验 token = hash(email + activityName + salt)
         */
        private String token;

        /**
         * 参与时间
         */
        private Instant participateTime;

        /**
         * IP 地址
         */
        private String ipAddress;
        
        /**
         * 是否中奖（即时开奖类型使用）
         */
        private Boolean isWinner;
        
        /**
         * 中奖奖品名称（即时开奖类型使用）
         */
        private String prizeName;
        
        /**
         * 中奖时间（即时开奖类型使用）
         */
        private Instant winTime;
    }
}
