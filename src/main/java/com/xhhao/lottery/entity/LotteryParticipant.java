package com.xhhao.lottery.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.time.Instant;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "lottery.xhhao.com", version = "v1alpha1", kind = "LotteryParticipant",
        plural = "lotteryparticipants", singular = "lotteryparticipant")
public class LotteryParticipant extends AbstractExtension {

    private LotteryParticipantSpec spec;

    @Data
    public static class LotteryParticipantSpec {
        private String activityName;
        
        private String activityTitle;

        private String email;

        private String displayName;

        private String username;

        private String commentName;

        private String token;

        private Instant participateTime;

        private String ipAddress;
        
        private Boolean isWinner;
        
        private String prizeName;
        
        private Instant winTime;
    }
}
