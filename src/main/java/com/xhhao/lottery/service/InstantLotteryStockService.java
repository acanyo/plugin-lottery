package com.xhhao.lottery.service;

import com.xhhao.lottery.entity.LotteryActivity;
import reactor.core.publisher.Mono;

import java.util.Optional;

public interface InstantLotteryStockService {

    Mono<Boolean> isAvailable();

    Mono<Optional<LotteryActivity.Prize>> reservePrize(LotteryActivity activity);

    Mono<Void> releasePrize(LotteryActivity activity, String prizeName);
}
