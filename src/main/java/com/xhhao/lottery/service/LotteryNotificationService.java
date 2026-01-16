package com.xhhao.lottery.service;

import com.xhhao.lottery.entity.LotteryActivity;
import com.xhhao.lottery.entity.LotteryParticipant;
import reactor.core.publisher.Mono;

public interface LotteryNotificationService {

    Mono<Void> sendParticipateNotification(LotteryParticipant participant, LotteryActivity activity);

    Mono<Void> sendWinningNotification(LotteryParticipant participant, LotteryActivity activity, String prizeName);

    Mono<Void> sendInstantNoPrizeNotification(LotteryParticipant participant, LotteryActivity activity);
}
