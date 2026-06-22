package robot.agent.channel.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import robot.agent.channel.core.UserChannelContext;
import robot.agent.channel.protocol.UserFrame;
import robot.agent.dto.request.SendMessageRequest;
import robot.agent.dto.response.SendMessageResponse;
import robot.agent.service.ExecutionCommandGateway;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TextMessageHandlerTest {

    @Test
    void handleCopiesRobotCodeFromChannelContextWhenMessageOmitsIt() {
        ExecutionCommandGateway gateway = mock(ExecutionCommandGateway.class);
        when(gateway.startExecution(eq("session-1"), any(SendMessageRequest.class))).thenReturn(new SendMessageResponse());
        TextMessageHandler handler = new TextMessageHandler(new ObjectMapper(), gateway);
        UserChannelContext context = new UserChannelContext("conn-1", mock(Channel.class), 10);
        UserFrame connectFrame = new UserFrame();
        connectFrame.setUserId("user-1");
        connectFrame.setSessionId("session-1");
        connectFrame.setPayload(Map.of("robot_code", "robot_after_sale"));
        context.bind(connectFrame);
        UserFrame messageFrame = new UserFrame();
        messageFrame.setPayload(Map.of("message", "hello"));

        handler.handle(context, messageFrame, mock(ChannelHandlerContext.class));

        ArgumentCaptor<SendMessageRequest> requestCaptor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(gateway).startExecution(eq("session-1"), requestCaptor.capture());
        assertThat(requestCaptor.getValue().getRobotCode()).isEqualTo("robot_after_sale");
    }
}
