package robot.agent.channel.handler;

import io.netty.channel.ChannelHandlerContext;
import org.springframework.stereotype.Component;
import robot.agent.channel.core.UserChannelContext;
import robot.agent.channel.protocol.UserFrame;

import java.util.Map;

@Component
public class HeartbeatHandler implements BusinessEventHandler {

    @Override
    public String eventType() {
        return "heartbeat.ping";
    }

    @Override
    public void handle(UserChannelContext context, UserFrame frame, ChannelHandlerContext nettyContext) {
        context.touch();
        nettyContext.writeAndFlush(UserFrame.response(frame, "heartbeat.pong", Map.of("pong", true)));
    }
}
