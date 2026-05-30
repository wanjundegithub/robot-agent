package robot.agent.channel.handler;

import io.netty.channel.ChannelHandlerContext;
import robot.agent.channel.core.UserChannelContext;
import robot.agent.channel.protocol.FrameType;
import robot.agent.channel.protocol.UserFrame;

public interface FrameHandler {
    FrameType frameType();

    void handle(UserChannelContext context, UserFrame frame, ChannelHandlerContext nettyContext);
}
