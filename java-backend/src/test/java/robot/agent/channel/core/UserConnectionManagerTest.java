package robot.agent.channel.core;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.Test;
import robot.agent.channel.netty.UserFrameEncoder;
import robot.agent.channel.protocol.FrameType;
import robot.agent.channel.protocol.UserFrame;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class UserConnectionManagerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void doesNotReplaceDifferentSessionsForSameUser() {
        UserConnectionManager manager = new UserConnectionManager(10);
        EmbeddedChannel firstChannel = new EmbeddedChannel(new UserFrameEncoder(objectMapper));
        EmbeddedChannel secondChannel = new EmbeddedChannel(new UserFrameEncoder(objectMapper));

        manager.register(firstChannel, connectFrame("demo-user", "session-a"));
        manager.register(secondChannel, connectFrame("demo-user", "session-b"));

        assertThat(firstChannel.isActive()).isTrue();
        assertThat(secondChannel.isActive()).isTrue();
        TextWebSocketFrame replaced = firstChannel.readOutbound();
        assertThat(replaced).isNull();
    }

    @Test
    void replacesOnlySameSessionConnection() {
        UserConnectionManager manager = new UserConnectionManager(10);
        EmbeddedChannel firstChannel = new EmbeddedChannel(new UserFrameEncoder(objectMapper));
        EmbeddedChannel secondChannel = new EmbeddedChannel(new UserFrameEncoder(objectMapper));

        manager.register(firstChannel, connectFrame("demo-user", "session-a"));
        manager.register(secondChannel, connectFrame("demo-user", "session-a"));

        TextWebSocketFrame replaced = firstChannel.readOutbound();
        assertThat(replaced.text()).contains("\"event_type\":\"connection.replaced\"");
        assertThat(replaced.text()).contains("\"session_id\":\"session-a\"");
        assertThat(replaced.text()).contains("same_session_new_connection");
    }

    private UserFrame connectFrame(String userId, String sessionId) {
        UserFrame frame = new UserFrame();
        frame.setFrame(FrameType.CONNECT.code());
        frame.setRequestId("init-" + sessionId);
        frame.setUserId(userId);
        frame.setSessionId(sessionId);
        frame.setEventType("connection.init");
        return frame;
    }
}
