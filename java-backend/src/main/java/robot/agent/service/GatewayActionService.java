package robot.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import robot.agent.dto.request.FormSubmitRequest;
import robot.agent.dto.request.SendMessageRequest;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class GatewayActionService {

    private static final Logger log = LoggerFactory.getLogger(GatewayActionService.class);

    private final ObjectMapper objectMapper;
    private final NettyGatewayHub gatewayHub;
    private final ExecutionCommandGateway executionGateway;

    public GatewayActionService(
            ObjectMapper objectMapper,
            NettyGatewayHub gatewayHub,
            ExecutionCommandGateway executionGateway
    ) {
        this.objectMapper = objectMapper;
        this.gatewayHub = gatewayHub;
        this.executionGateway = executionGateway;
    }

    public Mono<Void> handle(String rawMessage, NettyGatewayHub.GatewayConnection connection) {
        return Mono.fromRunnable(() -> process(rawMessage, connection))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private void process(String rawMessage, NettyGatewayHub.GatewayConnection connection) {
        Map<String, Object> envelope;
        try {
            envelope = objectMapper.readValue(rawMessage, new TypeReference<Map<String, Object>>() {});
        } catch (Exception exception) {
            log.warn("gateway.action.invalid_json connectionId={} message={}", connection.connectionId(), rawMessage, exception);
            gatewayHub.send(connection.connectionId(), error(null, "invalid_json", exception.getMessage()));
            return;
        }

        String requestId = stringValue(envelope.get("request_id"));
        String action = stringValue(envelope.get("action"));
        String sessionId = stringValue(envelope.get("session_id"));
        if (sessionId == null) {
            sessionId = connection.sessionId();
        }
        Map<String, Object> payload = asMap(envelope.get("payload"));
        log.info(
                "gateway.action.received connectionId={} sessionId={} requestId={} action={} payloadKeys={}",
                connection.connectionId(),
                sessionId,
                requestId,
                action,
                payload.keySet()
        );
        if (!"action".equals(stringValue(envelope.get("type"))) || action == null) {
            log.warn("gateway.action.invalid_envelope connectionId={} requestId={} payload={}", connection.connectionId(), requestId, envelope);
            gatewayHub.send(connection.connectionId(), error(requestId, "invalid_envelope", "Unsupported envelope type"));
            return;
        }

        try {
            switch (action) {
                case "send_message" -> {
                    SendMessageRequest request = objectMapper.convertValue(payload, SendMessageRequest.class);
                    gatewayHub.send(connection.connectionId(), ack(requestId, action, "accepted",
                            executionGateway.startExecution(sessionId, request)));
                    log.info("gateway.action.ack connectionId={} requestId={} action={} sessionId={}", connection.connectionId(), requestId, action, sessionId);
                }
                case "submit_form" -> {
                    FormSubmitRequest request = objectMapper.convertValue(payload, FormSubmitRequest.class);
                    String executionId = stringValue(payload.get("execution_id"));
                    gatewayHub.send(connection.connectionId(), ack(requestId, action, "accepted",
                            executionGateway.submitForm(executionId, request)));
                    log.info("gateway.action.ack connectionId={} requestId={} action={} executionId={}", connection.connectionId(), requestId, action, executionId);
                }
                case "resume_execution" -> {
                    String executionId = stringValue(payload.get("execution_id"));
                    gatewayHub.send(connection.connectionId(), ack(requestId, action, "accepted",
                            executionGateway.resumeExecution(executionId)));
                    log.info("gateway.action.ack connectionId={} requestId={} action={} executionId={}", connection.connectionId(), requestId, action, executionId);
                }
                default -> gatewayHub.send(connection.connectionId(), error(requestId, "unsupported_action", action));
            }
        } catch (Exception exception) {
            log.error(
                    "gateway.action.failed connectionId={} sessionId={} requestId={} action={} payload={}",
                    connection.connectionId(),
                    sessionId,
                    requestId,
                    action,
                    payload,
                    exception
            );
            gatewayHub.send(connection.connectionId(), error(requestId, "action_failed", formatException(exception)));
        }
    }

    private Map<String, Object> ack(String requestId, String action, String status, Object data) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", "ack");
        value.put("request_id", requestId);
        value.put("action", action);
        value.put("status", status);
        value.put("data", data);
        return value;
    }

    private Map<String, Object> error(String requestId, String errorCode, String message) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", "error");
        value.put("request_id", requestId);
        value.put("error_code", errorCode);
        value.put("message", message);
        return value;
    }

    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    converted.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return converted;
        }
        return new LinkedHashMap<>();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String formatException(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return exception.getClass().getSimpleName() + ": " + message;
    }
}
