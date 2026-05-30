package robot.agent.channel.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import robot.agent.channel.core.UserChannelContext;
import robot.agent.channel.protocol.UserFrame;
import robot.agent.dto.request.FormSubmitRequest;
import robot.agent.dto.response.FormSubmitResponse;
import robot.agent.service.ExecutionCommandGateway;

@Component
public class FormSubmitHandler implements BusinessEventHandler {

    private static final Logger log = LoggerFactory.getLogger(FormSubmitHandler.class);

    private final ObjectMapper objectMapper;
    private final ExecutionCommandGateway executionCommandGateway;

    public FormSubmitHandler(ObjectMapper objectMapper, ExecutionCommandGateway executionCommandGateway) {
        this.objectMapper = objectMapper;
        this.executionCommandGateway = executionCommandGateway;
    }

    @Override
    public String eventType() {
        return "form.submit";
    }

    @Override
    public void handle(UserChannelContext context, UserFrame frame, ChannelHandlerContext nettyContext) {
        String executionId = frame.getExecutionId();
        if (!hasText(executionId) && frame.getPayload().get("execution_id") != null) {
            executionId = String.valueOf(frame.getPayload().get("execution_id"));
        }
        try {
            FormSubmitRequest request = objectMapper.convertValue(frame.getPayload(), FormSubmitRequest.class);
            FormSubmitResponse response = executionCommandGateway.submitForm(executionId, request);
            nettyContext.writeAndFlush(UserFrame.ack(frame, "form.submitted", response));
        } catch (Exception exception) {
            log.error("channel.form_submit.failed connectionId={} executionId={}", context.connectionId(), executionId, exception);
            nettyContext.writeAndFlush(UserFrame.error(frame, "handler_failed", exception.getMessage()));
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
