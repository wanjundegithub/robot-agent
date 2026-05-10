package robot.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ExecutionWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ExecutionWebSocketHandler.class);
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> executionSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, String> sessionSubscriptions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public ExecutionWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
        executionSubscriptions.put(session.getId(), resolveQueryParam(session.getUri(), "execution_id"));
        sessionSubscriptions.put(session.getId(), resolveQueryParam(session.getUri(), "session_id"));
        log.info(
                "ws.execution.connected sessionId={} executionId={} wsSessionId={}",
                sessionSubscriptions.get(session.getId()),
                executionSubscriptions.get(session.getId()),
                session.getId()
        );
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info(
                "ws.execution.closed sessionId={} executionId={} wsSessionId={} code={}",
                sessionSubscriptions.get(session.getId()),
                executionSubscriptions.get(session.getId()),
                session.getId(),
                status == null ? null : status.getCode()
        );
        sessions.remove(session.getId());
        executionSubscriptions.remove(session.getId());
        sessionSubscriptions.remove(session.getId());
    }

    public void broadcast(Object payload) {
        long startedAt = System.currentTimeMillis();
        String json;
        String payloadExecutionId = resolvePayloadField(payload, "execution_id");
        String payloadSessionId = resolvePayloadField(payload, "session_id");
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (IOException e) {
            return;
        }
        int sentCount = 0;
        for (WebSocketSession session : sessions.values()) {
            if (!session.isOpen()) {
                continue;
            }
            String subscribedExecutionId = executionSubscriptions.get(session.getId());
            String subscribedSessionId = sessionSubscriptions.get(session.getId());
            if (subscribedExecutionId != null && payloadExecutionId != null && !payloadExecutionId.equals(subscribedExecutionId)) {
                continue;
            }
            if (subscribedSessionId != null && payloadSessionId != null && !payloadSessionId.equals(subscribedSessionId)) {
                continue;
            }
            try {
                synchronized (session) {
                    session.sendMessage(new TextMessage(json));
                }
                sentCount++;
            } catch (Exception ignored) {
            }
        }
        log.info(
                "ws.execution.broadcast payloadType={} sessionId={} executionId={} recipients={} durationMs={}",
                resolvePayloadField(payload, "type"),
                payloadSessionId,
                payloadExecutionId,
                sentCount,
                System.currentTimeMillis() - startedAt
        );
    }

    private String resolveQueryParam(URI uri, String key) {
        if (uri == null || uri.getQuery() == null || uri.getQuery().isBlank()) {
            return null;
        }
        for (String part : uri.getQuery().split("&")) {
            String[] keyValue = part.split("=", 2);
            if (keyValue.length == 2 && key.equals(keyValue[0])) {
                return keyValue[1];
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String resolvePayloadField(Object payload, String key) {
        if (!(payload instanceof Map<?, ?> map)) {
            return null;
        }
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
