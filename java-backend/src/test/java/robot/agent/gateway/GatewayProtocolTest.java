package robot.agent.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import robot.agent.gateway.protocol.GatewayEnvelope;
import robot.agent.service.GatewayActionService;
import robot.agent.service.NettyGatewayHub;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class GatewayProtocolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void gatewayEnvelopeDeserializesSnakeCaseFields() throws Exception {
        String json = """
                {
                  "request_id": "req-1",
                  "action": "chat.send",
                  "session_id": "sess-1",
                  "execution_id": "exec-1",
                  "payload": {
                    "message": "hello"
                  }
                }
                """;

        GatewayEnvelope envelope = objectMapper.readValue(json, GatewayEnvelope.class);

        assertEquals("req-1", envelope.getRequestId());
        assertEquals("chat.send", envelope.getAction());
        assertEquals("sess-1", envelope.getSessionId());
        assertEquals("exec-1", envelope.getExecutionId());
        assertEquals(Map.of("message", "hello"), envelope.getPayload());
    }

    @Test
    void robotChannelInitializerConfiguresHttpAndWebSocketHandlers() {
        NettyGatewayHub hub = new NettyGatewayHub(objectMapper);
        GatewayActionService actionService = mock(GatewayActionService.class);

        EmbeddedChannel channel = new EmbeddedChannel(
                new RobotChannelInitializer(objectMapper, hub, actionService)
        );

        assertNotNull(channel.pipeline().get(HttpServerCodec.class));
        WebSocketServerProtocolHandler webSocketHandler = channel.pipeline().get(WebSocketServerProtocolHandler.class);
        assertNotNull(webSocketHandler);
        WebSocketServerProtocolConfig serverConfig =
                (WebSocketServerProtocolConfig) ReflectionTestUtils.getField(webSocketHandler, "serverConfig");
        assertNotNull(serverConfig);
        assertEquals("/ws/robot", serverConfig.websocketPath());
        channel.finishAndReleaseAll();
    }
}
