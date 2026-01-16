package com.xhhao.lottery.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.time.Instant;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "lottery.xhhao.com", version = "v1alpha1", kind = "LotteryActivity",
        plural = "lotteryactivities", singular = "lotteryactivity")
public class LotteryActivity extends AbstractExtension {

    private LotteryActivitySpec spec;
    private LotteryActivityStatus status;

    public enum State {
        PENDING,    // 待开始
        RUNNING,    // 进行中
        ENDED,      // 已结束
        DRAWN       // 已开奖
    }

    public enum ParticipationType {
        NONE,               // 无条件，需邮箱
        LOGIN,              // 需登录
        COMMENT,            // 需评论
        LOGIN_AND_COMMENT   // 登录+评论
    }

    public enum LotteryType {
        SCHEDULED,  // 定时开奖（到时间统一抽取）
        WHEEL,      // 大转盘（参与即抽，立即出结果）
        DRAW        // 抽签（参与即抽，立即出结果）
    }

    @Data
    public static class LotteryActivitySpec {
        private String title;
        private String description;
        
        private LotteryType lotteryType;
        
        private ParticipationType participationType;
        
        private String targetPostName;
        
        private Instant startTime;
        private Instant endTime;
        
        private Integer maxParticipants;
        
        private Boolean allowDuplicate;

        private Instant drawTime;

        private List<Prize> prizes;
        
        private Integer thankYouSlots;
        
        public LotteryType getLotteryType() {
            return lotteryType != null ? lotteryType : LotteryType.SCHEDULED;
        }
        
        public Integer getThankYouSlots() {
            return thankYouSlots != null ? thankYouSlots : 2;
        }
    }

    @Data
    public static class Prize {
        private String name;
        private String description;
        private String imageUrl;
        private Integer quantity;
        private Integer remaining;
        private Integer probability;
    }

    @Data
    public static class LotteryActivityStatus {
        private State state;
        private Integer participantCount;
        private Instant drawnTime;
        private List<Winner> winners;
    }

    @Data
    public static class Winner {
        private String identifier;  // username 或 email
        private String prizeName;
        private Instant winTime;
    }
}
