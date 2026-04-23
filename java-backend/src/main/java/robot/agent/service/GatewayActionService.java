package robot.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import robot.agent.dto.request.FormSubmitRequest;
import robot.agent.dto.request.SendMessageRequest;
import robot.agent.gateway.protocol.GatewayEnvelope;

import java.time.OffsetDateTime;
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

    public Mono<Void> handle(GatewayEnvelope envelope, NettyGatewayHub.GatewayConnection connection) {
        return Mono.fromRunnable(() -> process(envelope, connection))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    public Mono<Void> handle(String rawMessage, NettyGatewayHub.GatewayConnection connection) {
        return Mono.fromCallable(() -> objectMapper.readValue(rawMessage, GatewayEnvelope.class))
                .flatMap(envelope -> handle(envelope, connection))
                .onErrorResume(error -> {
                    log.warn("gateway.action.invalid_json connectionId={} message={}", connection.connectionId(), rawMessage, error);
                    gatewayHub.send(connection.connectionId(), error(null, "invalid_json", error.getMessage()));
                    return Mono.empty();
                });
    }

    private void process(GatewayEnvelope envelope, NettyGatewayHub.GatewayConnection connection) {
        String requestId = envelope.getRequestId();
        String action = envelope.getAction();
        String normalizedAction = normalizeAction(action);
        String sessionId = envelope.getSessionId() == null || envelope.getSessionId().isBlank()
                ? connection.sessionId()
                : envelope.getSessionId();
        String executionId = envelope.getExecutionId();
        Map<String, Object> payload = envelope.getPayload() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(envelope.getPayload());
        if ((executionId == null || executionId.isBlank()) && payload.get("execution_id") != null) {
            executionId = String.valueOf(payload.get("execution_id"));
        }

        log.info(
                "gateway.action.received connectionId={} sessionId={} requestId={} action={} payloadKeys={}",
                connection.connectionId(),
                sessionId,
                requestId,
                action,
                payload.keySet()
        );

        try {
            switch (normalizedAction) {
                case "chat.send" -> {
                    SendMessageRequest request = objectMapper.convertValue(payload, SendMessageRequest.class);
                    gatewayHub.send(connection.connectionId(), ack(requestId, action, "accepted",
                            executionGateway.startExecution(sessionId, request)));
                    log.info("gateway.action.ack connectionId={} requestId={} action={} sessionId={}", connection.connectionId(), requestId, action, sessionId);
                }
                case "form.submit" -> {
                    FormSubmitRequest request = objectMapper.convertValue(payload, FormSubmitRequest.class);
                    gatewayHub.send(connection.connectionId(), ack(requestId, action, "accepted",
                            executionGateway.submitForm(executionId, request)));
                    log.info("gateway.action.ack connectionId={} requestId={} action={} executionId={}", connection.connectionId(), requestId, action, executionId);
                }
                case "execution.resume" -> {
                    gatewayHub.send(connection.connectionId(), ack(requestId, action, "accepted",
                            executionGateway.resumeExecution(executionId)));
                    log.info("gateway.action.ack connectionId={} requestId={} action={} executionId={}", connection.connectionId(), requestId, action, executionId);
                }
                case "ping" -> gatewayHub.send(connection.connectionId(), ack(
                        requestId,
                        action,
                        "accepted",
                        Map.of("pong", true)
                ));
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

    private String normalizeAction(String action) {
        if (action == null) {
            return "";
        }
        return switch (action) {
            case "send_message" -> "chat.send";
            case "submit_form" -> "form.submit";
            case "resume_execution" -> "execution.resume";
            default -> action;
        };
    }

    private Map<String, Object> ack(String requestId, String action, String status, Object data) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", "ack");
        value.put("request_id", requestId);
        value.put("action", action);
        value.put("status", status);
        value.put("data", data);
        value.put("timestamp", OffsetDateTime.now().toString());
        return value;
    }

    private Map<String, Object> error(String requestId, String errorCode, String message) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", "error");
        value.put("request_id", requestId);
        value.put("code", errorCode);
        value.put("error_code", errorCode);
        value.put("message", message);
        value.put("fatal", false);
        value.put("timestamp", OffsetDateTime.now().toString());
        return value;
    }

    private String formatException(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return exception.getClass().getSimpleName() + ": " + message;
    }
}
