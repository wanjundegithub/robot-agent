package robot.agent.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import robot.agent.service.GatewayActionService;
import robot.agent.service.NettyGatewayHub;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class RobotChannelInitializer extends ChannelInitializer<Channel> {

    private final ObjectMapper objectMapper;
    private final NettyGatewayHub gatewayHub;
    private final GatewayActionService gatewayActionService;

    public RobotChannelInitializer(
            ObjectMapper objectMapper,
            NettyGatewayHub gatewayHub,
            GatewayActionService gatewayActionService
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.gatewayHub = Objects.requireNonNull(gatewayHub, "gatewayHub");
        this.gatewayActionService = Objects.requireNonNull(gatewayActionService, "gatewayActionService");
    }

    @Override
    protected void initChannel(Channel channel) {
        WebSocketServerProtocolConfig webSocketConfig = WebSocketServerProtocolConfig.newBuilder()
                .websocketPath("/ws/robot")
                .allowExtensions(true)
                .checkStartsWith(true)
                .build();
        channel.pipeline()
                .addLast(new HttpServerCodec())
                .addLast(new HttpObjectAggregator(65536))
                .addLast(new WebSocketServerProtocolHandler(webSocketConfig))
                .addLast(new GatewayFrameHandler(objectMapper, gatewayHub, gatewayActionService));
    }

    private static final class GatewayFrameHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

        private static final Logger log = LoggerFactory.getLogger(GatewayFrameHandler.class);

        private final ObjectMapper objectMapper;
        private final NettyGatewayHub gatewayHub;
        private final GatewayActionService gatewayActionService;

        private GatewayFrameHandler(
                ObjectMapper objectMapper,
                NettyGatewayHub gatewayHub,
                GatewayActionService gatewayActionService
        ) {
            this.objectMapper = objectMapper;
            this.gatewayHub = gatewayHub;
            this.gatewayActionService = gatewayActionService;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
            NettyGatewayHub.GatewayConnection connection = gatewayHub.getByChannel(context.channel());
            if (connection == null) {
                connection = gatewayHub.register(context.channel(), null, null);
            }
            NettyGatewayHub.GatewayConnection finalConnection = connection;

            gatewayActionService.handle(frame.text(), finalConnection)
                    .doOnError(error -> log.warn(
                            "gateway.channel.handle_failed connectionId={} message={}",
                            finalConnection.connectionId(),
                            frame.text(),
                            error
                    ))
                    .onErrorComplete()
                    .subscribe();
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) throws Exception {
            gatewayHub.unregister(context.channel());
            super.channelInactive(context);
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext context, Object event) throws Exception {
            if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete handshake) {
                QueryStringDecoder decoder = new QueryStringDecoder(handshake.requestUri());
                gatewayHub.register(
                        context.channel(),
                        firstQueryValue(decoder.parameters(), "session_id"),
                        firstQueryValue(decoder.parameters(), "execution_id")
                );
            }
            super.userEventTriggered(context, event);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            log.warn("gateway.channel.exception channel={}", context.channel().id(), cause);
            context.close();
        }

        private String firstQueryValue(Map<String, List<String>> parameters, String key) {
            List<String> values = parameters.get(key);
            if (values == null || values.isEmpty()) {
                return null;
            }
            String value = values.get(0);
            if (value == null || value.isBlank()) {
                return null;
            }
            return value;
        }
    }
}
