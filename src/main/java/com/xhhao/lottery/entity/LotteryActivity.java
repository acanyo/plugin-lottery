package com.xhhao.lottery.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.time.Instant;
import java.util.List;

/**
 * 抽奖活动实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "lottery.xhhao.com", version = "v1alpha1", kind = "LotteryActivity",
        plural = "lotteryactivities", singular = "lotteryactivity")
public class LotteryActivity extends AbstractExtension {

    private LotteryActivitySpec spec;
    private LotteryActivityStatus status;

    /**
     * 活动状态
     */
    public enum State {
        PENDING,    // 待开始
        RUNNING,    // 进行中
        ENDED,      // 已结束
        DRAWN       // 已开奖
    }

    /**
     * 参与条件类型
     */
    public enum ParticipationType {
        NONE,               // 无条件，需邮箱
        LOGIN,              // 需登录
        COMMENT,            // 需评论
        LOGIN_AND_COMMENT   // 登录+评论
    }

    /**
     * 抽奖类型
     */
    public enum LotteryType {
        SCHEDULED,  // 定时开奖（到时间统一抽取）
        WHEEL,      // 大转盘（参与即抽，立即出结果）
        DRAW        // 抽签（参与即抽，立即出结果）
    }

    @Data
    public static class LotteryActivitySpec {
        private String title;
        private String description;
        
        /**
         * 抽奖类型，默认定时开奖
         */
        private LotteryType lotteryType;
        
        /**
         * 参与条件
         */
        private ParticipationType participationType;
        
        /**
         * 关联文章（COMMENT 类型时使用）
         */
        private String targetPostName;
        
        private Instant startTime;
        private Instant endTime;
        
        /**
         * 最大参与人数，null 不限
         */
        private Integer maxParticipants;
        
        /**
         * 是否允许重复参与
         */
        private Boolean allowDuplicate;

        /**
         * 定时开奖时间，null 表示手动开奖（仅 SCHEDULED 类型有效）
         */
        private Instant drawTime;

        /**
         * 奖品列表（内嵌）
         */
        private List<Prize> prizes;
        
        /**
         * "谢谢参与"格子数量（大转盘/刮刮乐使用，默认2）
         */
        private Integer thankYouSlots;
        
        /**
         * 获取抽奖类型，默认 SCHEDULED
         */
        public LotteryType getLotteryType() {
            return lotteryType != null ? lotteryType : LotteryType.SCHEDULED;
        }
        
        /**
         * 获取谢谢参与格子数量，默认2
         */
        public Integer getThankYouSlots() {
            return thankYouSlots != null ? thankYouSlots : 2;
        }
    }

    @Data
    public static class Prize {
        private String name;
        private String description;
        private String imageUrl;
        /**
         * 该奖品总数量
         */
        private Integer quantity;
        /**
         * 剩余数量（即时开奖类型使用）
         */
        private Integer remaining;
        /**
         * 中奖概率（0-100，即时开奖类型使用）
         */
        private Integer probability;
    }

    @Data
    public static class LotteryActivityStatus {
        private State state;
        private Integer participantCount;
        private Instant drawnTime;
        /**
         * 中奖者用户名/邮箱列表
         */
        private List<Winner> winners;
    }

    @Data
    public static class Winner {
        private String identifier;  // username 或 email
        private String prizeName;
        private Instant winTime;
    }
}
