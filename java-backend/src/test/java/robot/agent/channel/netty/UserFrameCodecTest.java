package robot.agent.channel.netty;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.Test;
import robot.agent.channel.protocol.FrameType;
import robot.agent.channel.protocol.UserFrame;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UserFrameCodecTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void decodesTextWebSocketFrameToUserFrame() {
        EmbeddedChannel channel = new EmbeddedChannel(new UserFrameDecoder(objectMapper));

        channel.writeInbound(new TextWebSocketFrame("""
                {"frame":8,"request_id":"req-1","user_id":"u-1","session_id":"s-1","event_type":"connection.init","payload":{"workflow_code":"demo"}}
                """));

        UserFrame frame = channel.readInbound();
        assertThat(frame.getFrame()).isEqualTo(FrameType.CONNECT.code());
        assertThat(frame.getRequestId()).isEqualTo("req-1");
        assertThat(frame.getUserId()).isEqualTo("u-1");
        assertThat(frame.getPayload()).containsEntry("workflow_code", "demo");
    }

    @Test
    void encodesUserFrameToTextWebSocketFrame() {
        EmbeddedChannel channel = new EmbeddedChannel(new UserFrameEncoder(objectMapper));
        UserFrame frame = new UserFrame();
        frame.setFrame(FrameType.INTERACTIVE.code());
        frame.setRequestId("req-2");
        frame.setUserId("u-1");
        frame.setSessionId("s-1");
        frame.setEventType("heartbeat.pong");
        frame.setPayload(Map.of("pong", true));

        channel.writeOutbound(frame);

        TextWebSocketFrame outbound = channel.readOutbound();
        assertThat(outbound.text()).contains("\"frame\":9");
        assertThat(outbound.text()).contains("\"event_type\":\"heartbeat.pong\"");
        assertThat(outbound.text()).contains("\"pong\":true");
    }
}
