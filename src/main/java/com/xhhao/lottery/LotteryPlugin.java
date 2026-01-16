package com.xhhao.lottery;

import com.xhhao.lottery.entity.LotteryActivity;
import com.xhhao.lottery.entity.LotteryParticipant;
import org.springframework.stereotype.Component;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.IndexSpecs;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

import java.util.Optional;

@Component
public class LotteryPlugin extends BasePlugin {

    private final SchemeManager schemeManager;

    public LotteryPlugin(PluginContext pluginContext, SchemeManager schemeManager) {
        super(pluginContext);
        this.schemeManager = schemeManager;
    }

    @Override
    public void start() {
        schemeManager.register(LotteryActivity.class, indexSpecs -> {
            indexSpecs.add(IndexSpecs.<LotteryActivity, String>single("status.state", String.class)
                .indexFunc(item -> Optional.ofNullable(item.getStatus())
                    .map(LotteryActivity.LotteryActivityStatus::getState)
                    .map(Enum::name)
                    .orElse(null)));
            indexSpecs.add(
                IndexSpecs.<LotteryActivity, String>single("spec.participationType", String.class)
                    .indexFunc(item -> Optional.ofNullable(item.getSpec())
                        .map(LotteryActivity.LotteryActivitySpec::getParticipationType)
                        .map(Enum::name)
                        .orElse(null)));
            indexSpecs.add(IndexSpecs.<LotteryActivity, String>single("spec.title", String.class)
                .indexFunc(item -> Optional.ofNullable(item.getSpec())
                    .map(LotteryActivity.LotteryActivitySpec::getTitle)
                    .orElse(null)));
        });
        schemeManager.register(LotteryParticipant.class, indexSpecs -> {
            indexSpecs.add(IndexSpecs.<LotteryParticipant, String>single("spec.token", String.class)
                .indexFunc(item -> Optional.ofNullable(item.getSpec())
                    .map(LotteryParticipant.LotteryParticipantSpec::getToken)
                    .orElse(null)));
            indexSpecs.add(
                IndexSpecs.<LotteryParticipant, String>single("spec.activityName", String.class)
                    .indexFunc(item -> Optional.ofNullable(item.getSpec())
                        .map(LotteryParticipant.LotteryParticipantSpec::getActivityName)
                        .orElse(null)));
            indexSpecs.add(
                IndexSpecs.<LotteryParticipant, String>single("spec.isWinner", String.class)
                    .indexFunc(item -> Optional.ofNullable(item.getSpec())
                        .map(LotteryParticipant.LotteryParticipantSpec::getIsWinner)
                        .map(String::valueOf)
                        .orElse(null)));
        });
    }

    @Override
    public void stop() {
        schemeManager.unregister(schemeManager.get(LotteryActivity.class));
        schemeManager.unregister(schemeManager.get(LotteryParticipant.class));
    }
}
