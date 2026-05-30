package robot.agent.channel.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.concurrent.EventExecutorGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import robot.agent.channel.core.UserChannelContext;
import robot.agent.channel.core.UserConnectionManager;
import robot.agent.channel.core.UserMessage;
import robot.agent.channel.core.UserMessageMailbox;
import robot.agent.channel.dispatch.UserEventDispatcher;
import robot.agent.channel.protocol.FrameType;
import robot.agent.channel.protocol.UserFrame;

import java.time.OffsetDateTime;
import java.util.Objects;

public class UserFrameInboundHandler extends SimpleChannelInboundHandler<UserFrame> {

    private static final Logger log = LoggerFactory.getLogger(UserFrameInboundHandler.class);

    private final UserConnectionManager connectionManager;
    private final UserEventDispatcher dispatcher;
    private final EventExecutorGroup userEventExecutorGroup;

    public UserFrameInboundHandler(
            UserConnectionManager connectionManager,
            UserEventDispatcher dispatcher,
            EventExecutorGroup userEventExecutorGroup
    ) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.userEventExecutorGroup = Objects.requireNonNull(userEventExecutorGroup, "userEventExecutorGroup");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, UserFrame frame) {
        UserChannelContext userContext = resolveContext(context, frame);
        if (userContext == null) {
            return;
        }
        userContext.touch();
        UserMessage message = new UserMessage(userContext, frame, context, OffsetDateTime.now());
        UserMessageMailbox mailbox = userContext.mailbox();
        if (!mailbox.offer(message)) {
            context.writeAndFlush(UserFrame.error(frame, "queue_overflow", "用户消息队列已满，请稍后重试"));
            return;
        }
        if (mailbox.startDraining()) {
            userEventExecutorGroup.next().execute(() -> drain(userContext));
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        connectionManager.unregister(context.channel());
        super.channelInactive(context);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        log.warn("channel.frame.exception channel={} message={}", context.channel().id(), cause.getMessage(), cause);
        context.writeAndFlush(UserFrame.error(null, "invalid_json", cause.getMessage()));
    }

    private UserChannelContext resolveContext(ChannelHandlerContext context, UserFrame frame) {
        if (frame.getFrame() == FrameType.CONNECT.code()) {
            if (!hasText(frame.getUserId()) || !hasText(frame.getSessionId())) {
                context.writeAndFlush(UserFrame.error(frame, "missing_user", "初始化帧必须包含 user_id 和 session_id"));
                return null;
            }
            return connectionManager.register(context.channel(), frame);
        }
        UserChannelContext userContext = connectionManager.current(context.channel());
        if (userContext == null) {
            context.writeAndFlush(UserFrame.error(frame, "connection_not_initialized", "连接尚未初始化"));
            return null;
        }
        if (hasText(frame.getUserId()) && userContext.userId() != null && !userContext.userId().equals(frame.getUserId())) {
            context.writeAndFlush(UserFrame.error(frame, "user_mismatch", "帧用户与连接用户不一致"));
            return null;
        }
        return userContext;
    }

    private void drain(UserChannelContext context) {
        UserMessageMailbox mailbox = context.mailbox();
        try {
            UserMessage message;
            while ((message = mailbox.poll()) != null) {
                try {
                    dispatcher.dispatch(message.userContext(), message.frame(), message.nettyContext());
                } catch (Exception exception) {
                    log.error("channel.frame.dispatch_failed connectionId={} userId={} eventType={}", context.connectionId(), context.userId(), message.frame().getEventType(), exception);
                    message.nettyContext().writeAndFlush(UserFrame.error(message.frame(), "handler_failed", exception.getMessage()));
                }
            }
        } finally {
            mailbox.stopDraining();
            if (!mailbox.isEmpty() && mailbox.startDraining()) {
                userEventExecutorGroup.next().execute(() -> drain(context));
            }
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
