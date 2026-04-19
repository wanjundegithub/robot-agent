package robot.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class NettyGatewayHub {

    private static final Logger log = LoggerFactory.getLogger(NettyGatewayHub.class);

    private final Map<String, GatewayConnection> connections = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public NettyGatewayHub(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public GatewayConnection register(String sessionId, String executionId) {
        String connectionId = UUID.randomUUID().toString();
        GatewayConnection connection = new GatewayConnection(
                connectionId,
                sessionId,
                executionId,
                Sinks.many().multicast().onBackpressureBuffer()
        );
        connections.put(connectionId, connection);
        log.debug("gateway.hub.register connectionId={} sessionId={} executionId={}", connectionId, sessionId, executionId);
        return connection;
    }

    public void unregister(String connectionId) {
        GatewayConnection connection = connections.remove(connectionId);
        if (connection != null) {
            log.debug("gateway.hub.unregister connectionId={} sessionId={} executionId={}", connection.connectionId(), connection.sessionId(), connection.executionId());
            connection.sink().tryEmitComplete();
        }
    }

    public void send(String connectionId, Map<String, Object> payload) {
        GatewayConnection connection = connections.get(connectionId);
        if (connection == null) {
            log.debug("gateway.hub.send.skipped connectionId={} reason=not_found", connectionId);
            return;
        }
        try {
            connection.sink().tryEmitNext(objectMapper.writeValueAsString(payload));
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

    public record GatewayConnection(
            String connectionId,
            String sessionId,
            String executionId,
            Sinks.Many<String> sink
    ) {
        public Flux<String> outboundFlux() {
            return sink.asFlux();
        }
    }
}
