package robot.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

@Component
@RefreshScope
@ConfigurationProperties(prefix = "robot.chat.fallback")
public class ChatFallbackProperties {

    private String modelUnavailableMessage = "当前模型服务暂时不可用，请稍后再试。";

    public String getModelUnavailableMessage() {
        return modelUnavailableMessage;
    }

    public void setModelUnavailableMessage(String modelUnavailableMessage) {
        if (modelUnavailableMessage == null || modelUnavailableMessage.isBlank()) {
            return;
        }
        this.modelUnavailableMessage = modelUnavailableMessage.trim();
    }
}
