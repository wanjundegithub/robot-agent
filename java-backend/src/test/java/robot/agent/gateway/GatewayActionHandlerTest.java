package robot.agent.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.Attribute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import robot.agent.dto.response.SendMessageResponse;
import robot.agent.service.ExecutionCommandGateway;
import robot.agent.service.NettyGatewayHub;
import robot.agent.service.WelcomeBootstrapService;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GatewayActionHandlerTest {

    @Mock
    private ExecutionCommandGateway executionGateway;

    @Mock
    private WelcomeBootstrapService welcomeBootstrapService;

    @Mock
    private ChannelHandlerContext context;

    @Mock
    private Channel channel;

    @Mock
    private ChannelId channelId;

    @Mock
    private ChannelFuture channelFuture;

    @Mock
    private Attribute<String> connectionIdAttr;

    private final AtomicReference<String> connectionIdRef = new AtomicReference<>();
    private GatewayActionHandler handler;
    private NettyGatewayHub gatewayHub;

    @BeforeEach
    void setUp() {
        gatewayHub = new NettyGatewayHub(new ObjectMapper());
        handler = new GatewayActionHandler(new ObjectMapper(), gatewayHub, executionGateway, welcomeBootstrapService);
        when(context.channel()).thenReturn(channel);
        lenient().when(channel.id()).thenReturn(channelId);
        when(channel.attr(any())).thenReturn((Attribute) connectionIdAttr);
        when(connectionIdAttr.get()).thenAnswer(invocation -> connectionIdRef.get());
        doAnswer(invocation -> {
            connectionIdRef.set(invocation.getArgument(0));
            return null;
        }).when(connectionIdAttr).set(anyString());
    }

    @Test
    void handshake_parses_workflow_binding_and_triggers_welcome_bootstrap() throws Exception {
        WebSocketServerProtocolHandler.HandshakeComplete event = new WebSocketServerProtocolHandler.HandshakeComplete(
                "/ws?session_id=session-1&execution_id=exec-1&workflow_code=hotel_booking&workflow_version=1.0.0",
                new DefaultHttpHeaders(),
                null
        );

        handler.userEventTriggered(context, event);

        verify(welcomeBootstrapService, timeout(1000)).bootstrap(
                any(),
                eq("session-1"),
                eq("hotel_booking"),
                eq("1.0.0")
        );

        NettyGatewayHub.GatewayConnection connection = gatewayHub.getByChannel(channel);
        assertThat(connection).isNotNull();
        assertThat(connection.sessionId()).isEqualTo("session-1");
        assertThat(connection.executionId()).isEqualTo("exec-1");
        assertThat(connection.workflowCode()).isEqualTo("hotel_booking");
        assertThat(connection.workflowVersion()).isEqualTo("1.0.0");
    }

    @Test
    void chatSend_doesNotBlockGatewayThreadWhileExecutionStarts() throws Exception {
        gatewayHub.register(channel, "session-1", null, null, null);
        CountDownLatch executionEntered = new CountDownLatch(1);
        CountDownLatch releaseExecution = new CountDownLatch(1);
        doAnswer(invocation -> {
            executionEntered.countDown();
            assertThat(releaseExecution.await(2, TimeUnit.SECONDS)).isTrue();
            SendMessageResponse response = new SendMessageResponse();
            response.setSessionId("session-1");
            response.setExecutionId("exec-1");
            response.setStatus("running");
            return response;
        }).when(executionGateway).startExecution(anyString(), any());

        AtomicReference<String> firstResponse = new AtomicReference<>();
        doAnswer(invocation -> {
            firstResponse.compareAndSet(null, invocation.getArgument(0, TextWebSocketFrame.class).text());
            return channelFuture;
        }).when(context).writeAndFlush(any(TextWebSocketFrame.class));

        String payload = new ObjectMapper().writeValueAsString(Map.of(
                "type", "action",
                "request_id", "req-1",
                "action", "chat.send",
                "session_id", "session-1",
                "payload", Map.of(
                        "session_id", "session-1",
                        "message_id", "msg-1",
                        "content", "定酒店",
                        "user_id", "demo-user"
                )
        ));

        long startedAt = System.currentTimeMillis();
        handler.channelRead0(context, new TextWebSocketFrame(payload));
        long durationMs = System.currentTimeMillis() - startedAt;

        assertThat(executionEntered.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(durationMs).isLessThan(500L);
        assertThat(firstResponse.get()).isNull();
        releaseExecution.countDown();

        verify(context, timeout(1000)).writeAndFlush(any(TextWebSocketFrame.class));
        assertThat(firstResponse.get()).contains("\"type\":\"ack\"");
        assertThat(firstResponse.get()).contains("\"status\":\"accepted\"");
    }
}
