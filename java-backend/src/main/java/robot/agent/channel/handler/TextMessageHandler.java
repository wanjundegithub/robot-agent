package robot.agent.channel.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import robot.agent.channel.core.UserChannelContext;
import robot.agent.channel.protocol.UserFrame;
import robot.agent.dto.request.SendMessageRequest;
import robot.agent.dto.response.SendMessageResponse;
import robot.agent.service.ExecutionCommandGateway;

@Component
public class TextMessageHandler implements BusinessEventHandler {

    private static final Logger log = LoggerFactory.getLogger(TextMessageHandler.class);

    private final ObjectMapper objectMapper;
    private final ExecutionCommandGateway executionCommandGateway;

    public TextMessageHandler(ObjectMapper objectMapper, ExecutionCommandGateway executionCommandGateway) {
        this.objectMapper = objectMapper;
        this.executionCommandGateway = executionCommandGateway;
    }

    @Override
    public String eventType() {
        return "message.text";
    }

    @Override
    public void handle(UserChannelContext context, UserFrame frame, ChannelHandlerContext nettyContext) {
        try {
            SendMessageRequest request = objectMapper.convertValue(frame.getPayload(), SendMessageRequest.class);
            if (!hasText(request.getSessionId())) {
                request.setSessionId(context.sessionId());
            }
            if (!hasText(request.getUserId())) {
                request.setUserId(context.userId());
            }
            if (!hasText(request.getWorkflowCode())) {
                request.setWorkflowCode(context.workflowCode());
            }
            if (!hasText(request.getWorkflowVersion())) {
                request.setWorkflowVersion(context.workflowVersion());
            }
            SendMessageResponse response = executionCommandGateway.startExecution(context.sessionId(), request);
            nettyContext.writeAndFlush(UserFrame.ack(frame, "message.accepted", response));
        } catch (Exception exception) {
            log.error("channel.message_text.failed connectionId={} userId={} sessionId={}", context.connectionId(), context.userId(), context.sessionId(), exception);
            nettyContext.writeAndFlush(UserFrame.error(frame, "handler_failed", exception.getMessage()));
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
