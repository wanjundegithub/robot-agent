package robot.agent.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import robot.agent.dto.request.FormSubmitRequest;
import robot.agent.dto.request.SendMessageRequest;
import robot.agent.dto.response.SendMessageResponse;
import robot.agent.gateway.protocol.GatewayEnvelope;
import robot.agent.service.ExecutionCommandGateway;
import robot.agent.service.NettyGatewayHub;
import robot.agent.service.WelcomeBootstrapService;

import java.util.concurrent.CompletableFuture;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
@Sharable
public class GatewayActionHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private static final Logger log = LoggerFactory.getLogger(GatewayActionHandler.class);

    private final ObjectMapper objectMapper;
    private final NettyGatewayHub gatewayHub;
    private final ExecutionCommandGateway executionGateway;
    private final WelcomeBootstrapService welcomeBootstrapService;

    public GatewayActionHandler(
            ObjectMapper objectMapper,
            NettyGatewayHub gatewayHub,
            ExecutionCommandGateway executionGateway,
            WelcomeBootstrapService welcomeBootstrapService
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.gatewayHub = Objects.requireNonNull(gatewayHub, "gatewayHub");
        this.executionGateway = Objects.requireNonNull(executionGateway, "executionGateway");
        this.welcomeBootstrapService = Objects.requireNonNull(welcomeBootstrapService, "welcomeBootstrapService");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) {
        long startedAt = System.currentTimeMillis();
        String rawMessage = frame.text();
        NettyGatewayHub.GatewayConnection connection = currentConnection(context);
        log.info(
                "gateway.channel.inbound connectionId={} frameSize={} sessionId={} executionId={}",
                connection.connectionId(),
                rawMessage == null ? 0 : rawMessage.length(),
                connection.sessionId(),
                connection.executionId()
        );

        try {
            GatewayEnvelope envelope = objectMapper.readValue(rawMessage, GatewayEnvelope.class);
            process(context, envelope, connection);
            log.info(
                    "gateway.channel.handled connectionId={} durationMs={}",
                    connection.connectionId(),
                    System.currentTimeMillis() - startedAt
            );
        } catch (JsonProcessingException exception) {
            log.warn("gateway.action.invalid_json connectionId={} message={}", connection.connectionId(), rawMessage, exception);
            writePayload(context, error(null, "invalid_json", exception.getMessage()));
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        gatewayHub.unregister(context.channel());
        super.channelInactive(context);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext context, Object event) throws Exception {
        if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete handshake) {
            QueryStringDecoder decoder = new QueryStringDecoder(handshake.requestUri());
            NettyGatewayHub.GatewayConnection connection = gatewayHub.register(
                    context.channel(),
                    firstQueryValue(decoder.parameters(), "session_id"),
                    firstQueryValue(decoder.parameters(), "execution_id"),
                    firstQueryValue(decoder.parameters(), "workflow_code"),
                    firstQueryValue(decoder.parameters(), "workflow_version")
            );
            boolean hasWorkflow = connection.workflowCode() != null && connection.workflowVersion() != null;
            log.info(
                    "gateway.channel.handshake connectionId={} channel={} hasSession={} hasExecution={} hasWorkflow={}",
                    connection.connectionId(),
                    context.channel().id(),
                    connection.sessionId() != null,
                    connection.executionId() != null,
                    hasWorkflow
            );
            triggerWelcomeBootstrap(connection);
        }
        super.userEventTriggered(context, event);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        log.warn("gateway.channel.exception channel={}", context.channel().id(), cause);
        context.close();
    }

    private NettyGatewayHub.GatewayConnection currentConnection(ChannelHandlerContext context) {
        NettyGatewayHub.GatewayConnection connection = gatewayHub.getByChannel(context.channel());
        if (connection != null) {
            return connection;
        }
        return gatewayHub.register(context.channel(), null, null, null, null);
    }

    private void triggerWelcomeBootstrap(NettyGatewayHub.GatewayConnection connection) {
        if (connection.sessionId() == null
                || connection.workflowCode() == null
                || connection.workflowVersion() == null) {
            log.info(
                    "welcome.bootstrap.skip connectionId={} reason=missing_binding hasSession={} hasWorkflowCode={} hasWorkflowVersion={}",
                    connection.connectionId(),
                    connection.sessionId() != null,
                    connection.workflowCode() != null,
                    connection.workflowVersion() != null
            );
            return;
        }
        CompletableFuture.runAsync(() -> welcomeBootstrapService.bootstrap(
                connection.connectionId(),
                connection.sessionId(),
                connection.workflowCode(),
                connection.workflowVersion()
        )).exceptionally(error -> {
            log.warn(
                    "welcome.bootstrap.failed connectionId={} sessionId={} workflowBound=true message={}",
                    connection.connectionId(),
                    connection.sessionId(),
                    error == null ? null : error.getMessage(),
                    error
            );
            return null;
        });
    }

    private void process(
            ChannelHandlerContext context,
            GatewayEnvelope envelope,
            NettyGatewayHub.GatewayConnection connection
    ) {
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
                "gateway.action.received connectionId={} sessionId={} requestId={} action={} normalizedAction={} payloadKeys={} connectionWorkflowCode={} connectionWorkflowVersion={}",
                connection.connectionId(),
                sessionId,
                requestId,
                action,
                normalizedAction,
                payload.keySet(),
                connection.workflowCode(),
                connection.workflowVersion()
        );

        try {
            switch (normalizedAction) {
                case "chat.send" -> {
                    SendMessageRequest request = objectMapper.convertValue(payload, SendMessageRequest.class);
                    log.info(
                            "gateway.chat_send.converted connectionId={} sessionId={} requestId={} messageId={} userId={} workflowCode={} workflowVersion={} contentLength={}",
                            connection.connectionId(),
                            sessionId,
                            requestId,
                            request.getMessageId(),
                            request.getUserId(),
                            request.getWorkflowCode(),
                            request.getWorkflowVersion(),
                            request.getContent() == null ? 0 : request.getContent().length()
                    );
                    CompletableFuture.runAsync(() -> dispatchChatSend(context, connection, sessionId, requestId, action, request));
                }
                case "form.submit" -> {
                    FormSubmitRequest request = objectMapper.convertValue(payload, FormSubmitRequest.class);
                    log.info(
                            "gateway.form_submit.dispatch connectionId={} executionId={} requestId={} submitId={}",
                            connection.connectionId(),
                            executionId,
                            requestId,
                            request.getSubmitId()
                    );
                    writePayload(context, ack(requestId, action, "accepted", executionGateway.submitForm(executionId, request)));
                    log.info("gateway.action.ack connectionId={} requestId={} action={} executionId={}", connection.connectionId(), requestId, action, executionId);
                }
                case "execution.resume" -> {
                    log.info("gateway.execution_resume.dispatch connectionId={} executionId={} requestId={}", connection.connectionId(), executionId, requestId);
                    writePayload(context, ack(requestId, action, "accepted", executionGateway.resumeExecution(executionId)));
                    log.info("gateway.action.ack connectionId={} requestId={} action={} executionId={}", connection.connectionId(), requestId, action, executionId);
                }
                case "ping" -> writePayload(context, ack(
                        requestId,
                        action,
                        "accepted",
                        Map.of("pong", true)
                ));
                default -> writePayload(context, error(requestId, "unsupported_action", action));
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
            writePayload(context, error(requestId, "action_failed", formatException(exception)));
        }
    }

    private void dispatchChatSend(
            ChannelHandlerContext context,
            NettyGatewayHub.GatewayConnection connection,
            String sessionId,
            String requestId,
            String action,
            SendMessageRequest request
    ) {
        try {
            log.info("gateway.chat_send.dispatch connectionId={} sessionId={} requestId={}", connection.connectionId(), sessionId, requestId);
            SendMessageResponse response = executionGateway.startExecution(sessionId, request);
            log.info(
                    "gateway.chat_send.result connectionId={} sessionId={} requestId={} executionId={} status={} workflowCode={} workflowVersion={}",
                    connection.connectionId(),
                    sessionId,
                    requestId,
                    response.getExecutionId(),
                    response.getStatus(),
                    response.getWorkflowCode(),
                    response.getWorkflowVersion()
            );
            writePayload(context, ack(requestId, action, "accepted", response));
            log.info("gateway.action.ack connectionId={} requestId={} action={} sessionId={} status=accepted mode=async", connection.connectionId(), requestId, action, sessionId);
        } catch (Exception exception) {
            log.error(
                    "gateway.chat_send.failed connectionId={} sessionId={} requestId={} action={}",
                    connection.connectionId(),
                    sessionId,
                    requestId,
                    action,
                    exception
            );
            writePayload(context, error(requestId, "action_failed", formatException(exception)));
        }
    }

    private void writePayload(ChannelHandlerContext context, Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            log.info(
                    "gateway.channel.write connectionId={} type={} requestId={} action={} status={} payloadKeys={}",
                    currentConnection(context).connectionId(),
                    payload.get("type"),
                    payload.get("request_id"),
                    payload.get("action"),
                    payload.get("status"),
                    payload.keySet()
            );
            context.writeAndFlush(new TextWebSocketFrame(json)).addListener(future -> {
                if (!future.isSuccess()) {
                    log.warn("gateway.channel.write_failed channel={} payloadKeys={}", context.channel().id(), payload.keySet(), future.cause());
                    return;
                }
                log.info(
                        "gateway.channel.write_success channel={} type={} requestId={} action={}",
                        context.channel().id(),
                        payload.get("type"),
                        payload.get("request_id"),
                        payload.get("action")
                );
            });
        } catch (Exception exception) {
            log.warn("gateway.channel.write_failed channel={} payloadKeys={}", context.channel().id(), payload.keySet(), exception);
            context.close();
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

    private String firstQueryValue(Map<String, List<String>> parameters, String key) {
        List<String> values = parameters.get(key);
        if (values == null || values.isEmpty()) {
            return null;
        }
        String value = values.get(0);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
