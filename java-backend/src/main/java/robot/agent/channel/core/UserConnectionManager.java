package robot.agent.channel.core;

import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import robot.agent.channel.protocol.FrameType;
import robot.agent.channel.protocol.UserFrame;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class UserConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(UserConnectionManager.class);

    private final ConcurrentMap<String, UserChannelContext> sessionContexts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, UserChannelContext> channelContexts = new ConcurrentHashMap<>();
    private final int mailboxCapacity;

    public UserConnectionManager(@Value("${robot.channel.mailbox-capacity:1000}") int mailboxCapacity) {
        this.mailboxCapacity = mailboxCapacity;
    }

    public UserChannelContext register(Channel channel, UserFrame frame) {
        UserChannelContext context = channel.attr(ChannelAttributes.USER_CONTEXT).get();
        if (context == null) {
            context = new UserChannelContext(UUID.randomUUID().toString(), channel, mailboxCapacity);
            channel.attr(ChannelAttributes.USER_CONTEXT).set(context);
            channel.attr(ChannelAttributes.CONNECTION_ID).set(context.connectionId());
            channelContexts.put(channel.id().asLongText(), context);
        }
        context.bind(frame);
        if (context.userId() != null) {
            channel.attr(ChannelAttributes.USER_ID).set(context.userId());
        }
        if (context.sessionId() != null) {
            UserChannelContext previous = sessionContexts.put(context.sessionId(), context);
            if (previous != null && previous != context && previous.channel().isActive()) {
                log.info(
                        "channel.session.replaced sessionId={} userId={} oldConnectionId={} newConnectionId={}",
                        context.sessionId(),
                        context.userId(),
                        previous.connectionId(),
                        context.connectionId()
                );
                previous.channel().writeAndFlush(replacedFrame(previous, context))
                        .addListener(future -> previous.channel().close());
            }
        }
        return context;
    }

    private UserFrame replacedFrame(UserChannelContext previous, UserChannelContext replacement) {
        UserFrame frame = new UserFrame();
        frame.setFrame(FrameType.CONNECT.code());
        frame.setUserId(previous.userId());
        frame.setSessionId(previous.sessionId());
        frame.setEventType("connection.replaced");
        frame.setPayload(Map.of(
                "reason", "same_session_new_connection",
                "replacement_connection_id", replacement.connectionId()
        ));
        frame.setTimestamp(java.time.OffsetDateTime.now().toString());
        return frame;
    }

    public UserChannelContext current(Channel channel) {
        return channel.attr(ChannelAttributes.USER_CONTEXT).get();
    }

    public UserChannelContext findBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return sessionContexts.get(sessionId);
    }

    public boolean sendEventFrame(String eventType, String executionId, String sessionId, Map<String, Object> payload) {
        UserChannelContext context = findBySessionId(sessionId);
        if (context == null || context.channel() == null || !context.channel().isActive()) {
            log.warn("channel.frame.no_active_channel eventType={} executionId={} sessionId={}", eventType, executionId, sessionId);
            return false;
        }
        UserFrame frame = new UserFrame();
        frame.setFrame(FrameType.INTERACTIVE.code());
        frame.setUserId(context.userId());
        frame.setSessionId(sessionId);
        frame.setExecutionId(executionId);
        frame.setEventType(eventType);
        frame.setPayload(payload == null ? Map.of() : payload);
        frame.setTimestamp(java.time.OffsetDateTime.now().toString());
        context.channel().writeAndFlush(frame);
        return true;
    }

    public boolean sendMessageDeltaFrame(String executionId, String sessionId, String content, Boolean isComplete) {
        return sendEventFrame(
                "message.delta",
                executionId,
                sessionId,
                Map.of(
                        "content", content == null ? "" : content,
                        "is_complete", isComplete != null && isComplete
                )
        );
    }

    public void unregister(Channel channel) {
        UserChannelContext context = current(channel);
        if (context == null) {
            return;
        }
        channelContexts.remove(channel.id().asLongText(), context);
        if (context.sessionId() != null) {
            sessionContexts.remove(context.sessionId(), context);
        }
        channel.attr(ChannelAttributes.USER_CONTEXT).set(null);
        channel.attr(ChannelAttributes.USER_ID).set(null);
        channel.attr(ChannelAttributes.CONNECTION_ID).set(null);
        log.info("channel.user.unregistered connectionId={} userId={} sessionId={}", context.connectionId(), context.userId(), context.sessionId());
    }
}
