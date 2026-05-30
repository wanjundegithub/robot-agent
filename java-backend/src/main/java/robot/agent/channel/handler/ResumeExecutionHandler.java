package robot.agent.channel.handler;

import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import robot.agent.channel.core.UserChannelContext;
import robot.agent.channel.protocol.UserFrame;
import robot.agent.dto.response.ResumeExecutionResponse;
import robot.agent.service.ExecutionCommandGateway;

@Component
public class ResumeExecutionHandler implements BusinessEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ResumeExecutionHandler.class);

    private final ExecutionCommandGateway executionCommandGateway;

    public ResumeExecutionHandler(ExecutionCommandGateway executionCommandGateway) {
        this.executionCommandGateway = executionCommandGateway;
    }

    @Override
    public String eventType() {
        return "execution.resume";
    }

    @Override
    public void handle(UserChannelContext context, UserFrame frame, ChannelHandlerContext nettyContext) {
        String executionId = frame.getExecutionId();
        if ((executionId == null || executionId.isBlank()) && frame.getPayload().get("execution_id") != null) {
            executionId = String.valueOf(frame.getPayload().get("execution_id"));
        }
        try {
            ResumeExecutionResponse response = executionCommandGateway.resumeExecution(executionId);
            nettyContext.writeAndFlush(UserFrame.ack(frame, "execution.resumed", response));
        } catch (Exception exception) {
            log.error("channel.execution_resume.failed connectionId={} executionId={}", context.connectionId(), executionId, exception);
            nettyContext.writeAndFlush(UserFrame.error(frame, "handler_failed", exception.getMessage()));
        }
    }
}
