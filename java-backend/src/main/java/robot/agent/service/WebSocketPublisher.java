package robot.agent.service;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

@Service
public class WebSocketPublisher {

    private static final Logger log = LoggerFactory.getLogger(WebSocketPublisher.class);

    private final NettyGatewayHub gatewayHub;

    public WebSocketPublisher(NettyGatewayHub gatewayHub) {
        this.gatewayHub = gatewayHub;
    }

    public void publishEvent(String eventType, String executionId, String sessionId, Map<String, Object> data) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "event");
        payload.put("event_type", eventType);
        payload.put("execution_id", executionId);
        payload.put("session_id", sessionId);
        payload.put("data", data == null ? Map.of() : data);
        log.debug("websocket.publish.event eventType={} executionId={} sessionId={} dataKeys={}", eventType, executionId, sessionId, data == null ? java.util.Set.of() : data.keySet());
        gatewayHub.publish(payload);
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
        log.debug("websocket.publish.delta executionId={} sessionId={} contentLength={} isComplete={}", executionId, sessionId, content == null ? 0 : content.length(), isComplete);
        gatewayHub.publish(payload);
    }

    public void publishMessageDelta(String executionId, String content, Boolean isComplete) {
        publishMessageDelta(executionId, null, content, isComplete);
    }
}
