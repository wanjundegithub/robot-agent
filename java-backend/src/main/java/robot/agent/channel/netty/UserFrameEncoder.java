package robot.agent.channel.netty;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import robot.agent.channel.protocol.UserFrame;

import java.util.List;
import java.util.Objects;

public class UserFrameEncoder extends MessageToMessageEncoder<UserFrame> {

    private final ObjectMapper objectMapper;

    public UserFrameEncoder(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    protected void encode(ChannelHandlerContext context, UserFrame frame, List<Object> out) throws Exception {
        out.add(new TextWebSocketFrame(objectMapper.writeValueAsString(frame)));
    }
}
