package com.xhhao.lottery.service;

import com.xhhao.lottery.entity.LotteryActivity;
import com.xhhao.lottery.entity.LotteryParticipant;
import com.xhhao.lottery.query.LotteryActivityQuery;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListResult;

public interface LotteryService {

    Mono<LotteryActivity> getActivity(String activityName);

    Mono<ListResult<LotteryActivity>> listActivities(LotteryActivityQuery query);

    Mono<LotteryActivity> draw(String activityName);

    Mono<LotteryParticipant> participateAnonymous(String activityName, String email, 
                                                   String displayName, String ipAddress);

    Mono<LotteryParticipant> participateWithLogin(String activityName, String ipAddress);

    Mono<LotteryParticipant> participateWithComment(String activityName, String postName, String ipAddress);

    Mono<LotteryParticipant> participateWithCommentByEmail(String activityName, String email, String postName, String ipAddress);

    Mono<LotteryParticipant> participateWithLoginAndComment(String activityName, String postName, String ipAddress);

    Mono<LotteryParticipant> findByToken(String token);

    Mono<LotteryParticipant> findByActivityAndEmail(String activityName, String email);

    Mono<LotteryActivity.Winner> getWinnerByToken(String activityName, String token);

    String generateToken(String activityName, String identifier);

    Mono<CommentCheckResult> checkComment(String postName, String email);

    @lombok.Data
    class CommentCheckResult {
        private Boolean hasCommented;
        private Boolean isLoggedIn;
        private String email;
        private String message;
    }
}
