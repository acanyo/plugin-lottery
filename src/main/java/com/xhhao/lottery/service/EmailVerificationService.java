package com.xhhao.lottery.service;

import reactor.core.publisher.Mono;

public interface EmailVerificationService {

    Mono<SendCodeResult> sendCode(String email, String activityName);

    Mono<Boolean> verifyCode(String email, String activityName, String code);

    Mono<Boolean> isVerificationEnabled();

    record SendCodeResult(boolean success, String message) {}
}
