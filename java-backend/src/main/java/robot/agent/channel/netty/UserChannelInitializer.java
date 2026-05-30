package robot.agent.channel.netty;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.concurrent.EventExecutorGroup;
import robot.agent.channel.core.UserConnectionManager;
import robot.agent.channel.dispatch.UserEventDispatcher;

import java.util.Objects;

public class UserChannelInitializer extends ChannelInitializer<Channel> {

    private final ObjectMapper objectMapper;
    private final UserConnectionManager connectionManager;
    private final UserEventDispatcher dispatcher;
    private final EventExecutorGroup userEventExecutorGroup;

    public UserChannelInitializer(
            ObjectMapper objectMapper,
            UserConnectionManager connectionManager,
            UserEventDispatcher dispatcher,
            EventExecutorGroup userEventExecutorGroup
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.userEventExecutorGroup = Objects.requireNonNull(userEventExecutorGroup, "userEventExecutorGroup");
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
                .addLast(new UserFrameDecoder(objectMapper))
                .addLast(new UserFrameEncoder(objectMapper))
                .addLast(userEventExecutorGroup, new UserFrameInboundHandler(connectionManager, dispatcher, userEventExecutorGroup));
    }
}
