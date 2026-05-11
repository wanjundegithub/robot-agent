package robot.agent.gateway;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.concurrent.EventExecutorGroup;

import java.util.Objects;

public class RobotChannelInitializer extends ChannelInitializer<Channel> {

    private final GatewayActionHandler gatewayActionHandler;
    private final EventExecutorGroup gatewayHandlerGroup;

    public RobotChannelInitializer(
            GatewayActionHandler gatewayActionHandler,
            EventExecutorGroup gatewayHandlerGroup
    ) {
        this.gatewayActionHandler = Objects.requireNonNull(gatewayActionHandler, "gatewayActionHandler");
        this.gatewayHandlerGroup = Objects.requireNonNull(gatewayHandlerGroup, "gatewayHandlerGroup");
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
                .addLast(gatewayHandlerGroup, gatewayActionHandler);
    }
}
