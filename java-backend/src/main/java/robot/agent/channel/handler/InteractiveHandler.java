package robot.agent.channel.handler;

import io.netty.channel.ChannelHandlerContext;
import org.springframework.stereotype.Component;
import robot.agent.channel.core.UserChannelContext;
import robot.agent.channel.dispatch.UserEventHandlerRegistry;
import robot.agent.channel.protocol.FrameType;
import robot.agent.channel.protocol.UserFrame;

@Component
public class InteractiveHandler implements FrameHandler {

    private final UserEventHandlerRegistry registry;

    public InteractiveHandler(UserEventHandlerRegistry registry) {
        this.registry = registry;
    }

    @Override
    public FrameType frameType() {
        return FrameType.INTERACTIVE;
    }

    @Override
    public void handle(UserChannelContext context, UserFrame frame, ChannelHandlerContext nettyContext) {
        registry.businessHandler(frame.getEventType())
                .ifPresentOrElse(
                        handler -> handler.handle(context, frame, nettyContext),
                        () -> nettyContext.writeAndFlush(UserFrame.error(frame, "unsupported_event", "不支持的事件类型"))
                );
    }
}
