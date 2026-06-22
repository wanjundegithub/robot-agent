package robot.agent.channel.handler;

import io.netty.channel.ChannelHandlerContext;
import org.springframework.stereotype.Component;
import robot.agent.channel.core.UserChannelContext;
import robot.agent.channel.protocol.FrameType;
import robot.agent.channel.protocol.UserFrame;
import robot.agent.service.WelcomeBootstrapService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
public class ConnectHandler implements FrameHandler {

    private final WelcomeBootstrapService welcomeBootstrapService;

    public ConnectHandler(WelcomeBootstrapService welcomeBootstrapService) {
        this.welcomeBootstrapService = welcomeBootstrapService;
    }

    @Override
    public FrameType frameType() {
        return FrameType.CONNECT;
    }

    @Override
    public void handle(UserChannelContext context, UserFrame frame, ChannelHandlerContext nettyContext) {
        context.bind(frame);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("connection_id", context.connectionId());
        payload.put("robot_code", context.robotCode());
        payload.put("workflow_code", context.workflowCode());
        payload.put("workflow_version", context.workflowVersion());
        nettyContext.writeAndFlush(UserFrame.response(frame, "connection.initialized", payload));
        if (hasText(context.workflowCode()) && hasText(context.workflowVersion())) {
            CompletableFuture.runAsync(() -> welcomeBootstrapService.bootstrap(
                    context.connectionId(),
                    context.sessionId(),
                    context.workflowCode(),
                    context.workflowVersion()
            ));
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
