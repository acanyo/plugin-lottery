package com.xhhao.lottery;

import java.util.Properties;
import lombok.RequiredArgsConstructor;
import org.pf4j.PluginWrapper;
import org.springframework.stereotype.Component;
import org.springframework.util.PropertyPlaceholderHelper;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.model.IModel;
import org.thymeleaf.model.IModelFactory;
import org.thymeleaf.processor.element.IElementModelStructureHandler;
import reactor.core.publisher.Mono;
import run.halo.app.theme.dialect.TemplateHeadProcessor;

@Component
@RequiredArgsConstructor
public class LotteryHeadProcessor implements TemplateHeadProcessor {

    static final PropertyPlaceholderHelper PROPERTY_PLACEHOLDER_HELPER =
        new PropertyPlaceholderHelper("${", "}");

    private final PluginWrapper pluginWrapper;

    @Override
    public Mono<Void> process(ITemplateContext context, IModel model,
        IElementModelStructureHandler structureHandler) {
        final IModelFactory modelFactory = context.getModelFactory();
        model.add(modelFactory.createText(componentScript()));
        return Mono.empty();
    }

    private String componentScript() {
        final Properties properties = new Properties();
        properties.setProperty("version", pluginWrapper.getDescriptor().getVersion());

        return PROPERTY_PLACEHOLDER_HELPER.replacePlaceholders("""
            <!-- lottery-card start -->
            <script defer src="/plugins/lottery/assets/static/lottery-card.js?version=${version}"></script>
            <link rel="stylesheet" href="/plugins/lottery/assets/static/lottery-card.css?version=${version}">
            <!-- lottery-card end -->
            """, properties);
    }
}
