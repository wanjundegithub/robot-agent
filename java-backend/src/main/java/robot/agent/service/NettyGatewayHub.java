package robot.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class NettyGatewayHub {

    private static final Logger log = LoggerFactory.getLogger(NettyGatewayHub.class);

    private static final AttributeKey<String> CONNECTION_ID_ATTR = AttributeKey.valueOf("robot.gateway.connectionId");

    private final ConcurrentMap<String, GatewayConnection> connections = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public NettyGatewayHub(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public GatewayConnection register(Channel channel, String sessionId, String executionId) {
        String existingId = channel.attr(CONNECTION_ID_ATTR).get();
        if (existingId != null) {
            GatewayConnection existing = connections.get(existingId);
            if (existing != null) {
                existing.bind(sessionId, executionId);
                return existing;
            }
        }

        String connectionId = UUID.randomUUID().toString();
        GatewayConnection connection = new GatewayConnection(connectionId, channel, sessionId, executionId);
        connections.put(connectionId, connection);
        channel.attr(CONNECTION_ID_ATTR).set(connectionId);
        log.debug("gateway.hub.register connectionId={} sessionId={} executionId={}", connectionId, sessionId, executionId);
        return connection;
    }

    public GatewayConnection register(String sessionId, String executionId) {
        String connectionId = UUID.randomUUID().toString();
        GatewayConnection connection = new GatewayConnection(connectionId, null, sessionId, executionId);
        connections.put(connectionId, connection);
        log.debug("gateway.hub.register.compat connectionId={} sessionId={} executionId={}", connectionId, sessionId, executionId);
        return connection;
    }

    public void bind(Channel channel, String sessionId, String executionId) {
        GatewayConnection connection = getByChannel(channel);
        if (connection == null) {
            register(channel, sessionId, executionId);
            return;
        }
        connection.bind(sessionId, executionId);
        log.debug(
                "gateway.hub.bind connectionId={} sessionId={} executionId={}",
                connection.connectionId(),
                connection.sessionId(),
                connection.executionId()
        );
    }

    public GatewayConnection getByChannel(Channel channel) {
        String connectionId = channel.attr(CONNECTION_ID_ATTR).get();
        if (connectionId == null) {
            return null;
        }
        return connections.get(connectionId);
    }

    public void unregister(Channel channel) {
        String connectionId = channel.attr(CONNECTION_ID_ATTR).getAndSet(null);
        if (connectionId != null) {
            unregister(connectionId);
        }
    }

    public void unregister(String connectionId) {
        GatewayConnection connection = connections.remove(connectionId);
        if (connection != null) {
            log.debug(
                    "gateway.hub.unregister connectionId={} sessionId={} executionId={}",
                    connection.connectionId(),
                    connection.sessionId(),
                    connection.executionId()
            );
        }
    }

    public void send(String connectionId, Map<String, Object> payload) {
        GatewayConnection connection = connections.get(connectionId);
        if (connection == null || connection.channel() == null) {
            log.debug("gateway.hub.send.skipped connectionId={} reason=not_found", connectionId);
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(payload);
            connection.write(json);
        } catch (Exception exception) {
            log.warn("gateway.hub.send.failed connectionId={} payloadKeys={}", connectionId, payload.keySet(), exception);
        }
    }

    public void publish(Map<String, Object> payload) {
        String payloadExecutionId = stringValue(payload.get("execution_id"));
        String payloadSessionId = stringValue(payload.get("session_id"));
        log.debug("gateway.hub.publish type={} sessionId={} executionId={}", payload.get("type"), payloadSessionId, payloadExecutionId);
        for (GatewayConnection connection : connections.values()) {
            if (connection.executionId() != null && payloadExecutionId != null && !payloadExecutionId.equals(connection.executionId())) {
                continue;
            }
            if (connection.sessionId() != null && payloadSessionId != null && !payloadSessionId.equals(connection.sessionId())) {
                continue;
            }
            send(connection.connectionId(), payload);
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public static final class GatewayConnection {

        private final String connectionId;
        private final Channel channel;
        private volatile String sessionId;
        private volatile String executionId;

        private GatewayConnection(String connectionId, Channel channel, String sessionId, String executionId) {
            this.connectionId = connectionId;
            this.channel = channel;
            this.sessionId = sessionId;
            this.executionId = executionId;
        }

        public String connectionId() {
            return connectionId;
        }

        public Channel channel() {
            return channel;
        }

        public String sessionId() {
            return sessionId;
        }

        public String executionId() {
            return executionId;
        }

        private void bind(String sessionId, String executionId) {
            if (sessionId != null && !sessionId.isBlank()) {
                this.sessionId = sessionId;
            }
            if (executionId != null && !executionId.isBlank()) {
                this.executionId = executionId;
            }
        }

        private void write(String json) {
            if (channel == null || !channel.isActive()) {
                return;
            }
            Runnable task = () -> {
                if (channel.isActive()) {
                    channel.writeAndFlush(new TextWebSocketFrame(json));
                }
            };
            if (channel.eventLoop().inEventLoop()) {
                task.run();
            } else {
                channel.eventLoop().execute(task);
            }
        }
    }
}
