package robot.agent.channel.core;

import io.netty.channel.Channel;
import robot.agent.channel.protocol.UserFrame;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class UserChannelContext {

    private final String connectionId;
    private final Channel channel;
    private final UserMessageMailbox mailbox;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final OffsetDateTime connectedAt;
    private volatile OffsetDateTime lastActiveAt;
    private volatile String userId;
    private volatile String sessionId;
    private volatile String executionId;
    private volatile String workflowCode;
    private volatile String workflowVersion;

    public UserChannelContext(String connectionId, Channel channel, int mailboxCapacity) {
        this.connectionId = Objects.requireNonNull(connectionId, "connectionId");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.mailbox = new UserMessageMailbox(mailboxCapacity);
        this.connectedAt = OffsetDateTime.now();
        this.lastActiveAt = connectedAt;
    }

    public void bind(UserFrame frame) {
        if (hasText(frame.getUserId())) {
            this.userId = frame.getUserId();
        }
        if (hasText(frame.getSessionId())) {
            this.sessionId = frame.getSessionId();
        }
        if (hasText(frame.getExecutionId())) {
            this.executionId = frame.getExecutionId();
        }
        Object workflowCodeValue = frame.getPayload().get("workflow_code");
        Object workflowVersionValue = frame.getPayload().get("workflow_version");
        if (workflowCodeValue != null && hasText(String.valueOf(workflowCodeValue))) {
            this.workflowCode = String.valueOf(workflowCodeValue);
        }
        if (workflowVersionValue != null && hasText(String.valueOf(workflowVersionValue))) {
            this.workflowVersion = String.valueOf(workflowVersionValue);
        }
        touch();
    }

    public void touch() {
        lastActiveAt = OffsetDateTime.now();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public String connectionId() {
        return connectionId;
    }

    public Channel channel() {
        return channel;
    }

    public UserMessageMailbox mailbox() {
        return mailbox;
    }

    public Map<String, Object> attributes() {
        return attributes;
    }

    public OffsetDateTime connectedAt() {
        return connectedAt;
    }

    public OffsetDateTime lastActiveAt() {
        return lastActiveAt;
    }

    public String userId() {
        return userId;
    }

    public String sessionId() {
        return sessionId;
    }

    public String executionId() {
        return executionId;
    }

    public String workflowCode() {
        return workflowCode;
    }

    public String workflowVersion() {
        return workflowVersion;
    }
}
