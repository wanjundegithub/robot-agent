package robot.agent.channel.handler;

import io.netty.channel.ChannelHandlerContext;
import robot.agent.channel.core.UserChannelContext;
import robot.agent.channel.protocol.UserFrame;

public interface BusinessEventHandler {
    String eventType();

    void handle(UserChannelContext context, UserFrame frame, ChannelHandlerContext nettyContext);

    default int order() {
        return 0;
    }
}
