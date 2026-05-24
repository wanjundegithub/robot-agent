package robot.agent.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.Attribute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import robot.agent.service.ExecutionCommandGateway;
import robot.agent.service.NettyGatewayHub;
import robot.agent.service.WelcomeBootstrapService;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
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
    private Attribute<String> connectionIdAttr;

    private final AtomicReference<String> connectionIdRef = new AtomicReference<>();
    private GatewayActionHandler handler;
    private NettyGatewayHub gatewayHub;

    @BeforeEach
    void setUp() {
        gatewayHub = new NettyGatewayHub(new ObjectMapper());
        handler = new GatewayActionHandler(new ObjectMapper(), gatewayHub, executionGateway, welcomeBootstrapService);
        when(context.channel()).thenReturn(channel);
        when(channel.id()).thenReturn(channelId);
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
}
