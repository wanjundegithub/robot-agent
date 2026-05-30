package robot.agent.channel.netty;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import robot.agent.channel.protocol.UserFrame;

import java.util.List;
import java.util.Objects;

public class UserFrameDecoder extends MessageToMessageDecoder<TextWebSocketFrame> {

    private final ObjectMapper objectMapper;

    public UserFrameDecoder(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    protected void decode(ChannelHandlerContext context, TextWebSocketFrame frame, List<Object> out) throws Exception {
        out.add(objectMapper.readValue(frame.text(), UserFrame.class));
    }
}
