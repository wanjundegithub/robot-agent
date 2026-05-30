package robot.agent.channel.dispatch;

import io.netty.channel.ChannelHandlerContext;
import org.springframework.stereotype.Component;
import robot.agent.channel.core.UserChannelContext;
import robot.agent.channel.handler.FrameHandler;
import robot.agent.channel.protocol.FrameType;
import robot.agent.channel.protocol.UserFrame;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class UserEventDispatcher {

    private final Map<FrameType, FrameHandler> frameHandlers;

    public UserEventDispatcher(List<FrameHandler> handlers) {
        Map<FrameType, FrameHandler> values = new EnumMap<>(FrameType.class);
        for (FrameHandler handler : handlers) {
            FrameHandler previous = values.put(handler.frameType(), handler);
            if (previous != null) {
                throw new IllegalStateException("Duplicate frame handler: " + handler.frameType());
            }
        }
        this.frameHandlers = Map.copyOf(values);
    }

    public void dispatch(UserChannelContext context, UserFrame frame, ChannelHandlerContext nettyContext) {
        FrameType.fromCode(frame.getFrame())
                .flatMap(type -> java.util.Optional.ofNullable(frameHandlers.get(type)))
                .ifPresentOrElse(
                        handler -> handler.handle(context, frame, nettyContext),
                        () -> nettyContext.writeAndFlush(UserFrame.error(frame, "unsupported_frame", "不支持的帧类型"))
                );
    }
}
