package com.xhhao.lottery.service.impl;

import com.xhhao.lottery.service.SettingConfigGetter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

@Component
@RequiredArgsConstructor
public class SettingConfigGetterImpl implements SettingConfigGetter {
    
    private final ReactiveSettingFetcher settingFetcher;

    @Override
    public Mono<NotificationConfig> getNotificationConfig() {
        return settingFetcher.fetch(NotificationConfig.GROUP, NotificationConfig.class)
            .defaultIfEmpty(new NotificationConfig());
    }

    @Override
    public Mono<VerificationConfig> getVerificationConfig() {
        return settingFetcher.fetch(VerificationConfig.GROUP, VerificationConfig.class)
            .defaultIfEmpty(new VerificationConfig());
    }
}
