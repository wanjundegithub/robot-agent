package robot.agent.channel.dispatch;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import robot.agent.channel.core.UserChannelContext;
import robot.agent.channel.handler.BusinessEventHandler;
import robot.agent.channel.handler.FrameHandler;
import robot.agent.channel.handler.InteractiveHandler;
import robot.agent.channel.netty.UserFrameEncoder;
import robot.agent.channel.protocol.FrameType;
import robot.agent.channel.protocol.UserFrame;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserEventDispatcherTest {

    @Test
    void dispatchesInteractiveFrameToBusinessHandler() {
        AtomicBoolean handled = new AtomicBoolean(false);
        BusinessEventHandler businessHandler = new BusinessEventHandler() {
            @Override
            public String eventType() {
                return "heartbeat.ping";
            }

            @Override
            public void handle(UserChannelContext context, UserFrame frame, io.netty.channel.ChannelHandlerContext nettyContext) {
                handled.set(true);
            }
        };
        UserEventHandlerRegistry registry = new UserEventHandlerRegistry(List.of(businessHandler));
        FrameHandler interactiveHandler = new InteractiveHandler(registry);
        UserEventDispatcher dispatcher = new UserEventDispatcher(List.of(interactiveHandler));
        EmbeddedChannel channel = new EmbeddedChannel(new UserFrameEncoder(new ObjectMapper()));
        UserFrame frame = new UserFrame();
        frame.setFrame(FrameType.INTERACTIVE.code());
        frame.setEventType("heartbeat.ping");

        dispatcher.dispatch(null, frame, channel.pipeline().firstContext());

        assertThat(handled).isTrue();
    }

    @Test
    void rejectsDuplicateBusinessHandlers() {
        BusinessEventHandler first = eventHandler("message.text");
        BusinessEventHandler second = eventHandler("message.text");

        assertThatThrownBy(() -> new UserEventHandlerRegistry(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate business event handler");
    }

    private BusinessEventHandler eventHandler(String eventType) {
        return new BusinessEventHandler() {
            @Override
            public String eventType() {
                return eventType;
            }

            @Override
            public void handle(UserChannelContext context, UserFrame frame, io.netty.channel.ChannelHandlerContext nettyContext) {
            }
        };
    }
}
