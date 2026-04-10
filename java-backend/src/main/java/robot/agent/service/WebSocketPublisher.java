package robot.agent.service;

import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class WebSocketPublisher {

    private final ExecutionWebSocketHandler webSocketHandler;

    public WebSocketPublisher(ExecutionWebSocketHandler webSocketHandler) {
        this.webSocketHandler = webSocketHandler;
    }

    public void publishEvent(String eventType, String executionId, String sessionId, Map<String, Object> data) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "event");
        payload.put("event_type", eventType);
        payload.put("execution_id", executionId);
        payload.put("session_id", sessionId);
        payload.put("data", data == null ? Map.of() : data);
        webSocketHandler.broadcast(payload);
    }

    public void publishEvent(String eventType, String executionId, Map<String, Object> data) {
        publishEvent(eventType, executionId, null, data);
    }

    public void publishMessageDelta(String executionId, String sessionId, String content, Boolean isComplete) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "message_delta");
        payload.put("execution_id", executionId);
        payload.put("session_id", sessionId);
        payload.put("content", content);
        payload.put("is_complete", isComplete != null && isComplete);
        webSocketHandler.broadcast(payload);
    }

    public void publishMessageDelta(String executionId, String content, Boolean isComplete) {
        publishMessageDelta(executionId, null, content, isComplete);
    }
}
