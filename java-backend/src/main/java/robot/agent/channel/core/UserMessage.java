package robot.agent.channel.core;

import io.netty.channel.ChannelHandlerContext;
import robot.agent.channel.protocol.UserFrame;

import java.time.OffsetDateTime;

public record UserMessage(
        UserChannelContext userContext,
        UserFrame frame,
        ChannelHandlerContext nettyContext,
        OffsetDateTime receivedAt
) {
}
