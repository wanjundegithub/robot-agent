package robot.agent.channel.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserFrame {

    private int frame;

    @JsonProperty("request_id")
    private String requestId;

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("session_id")
    private String sessionId;

    @JsonProperty("execution_id")
    private String executionId;

    @JsonProperty("event_type")
    private String eventType;

    private Map<String, Object> payload = new LinkedHashMap<>();

    private String timestamp;

    public static UserFrame response(UserFrame request, String eventType, Object payload) {
        UserFrame frame = new UserFrame();
        frame.setFrame(request.getFrame());
        frame.setRequestId(request.getRequestId());
        frame.setUserId(request.getUserId());
        frame.setSessionId(request.getSessionId());
        frame.setExecutionId(request.getExecutionId());
        frame.setEventType(eventType);
        frame.setPayload(asPayload(payload));
        frame.setTimestamp(OffsetDateTime.now().toString());
        return frame;
    }

    public static UserFrame error(UserFrame request, String code, String message) {
        int frameCode = request == null || request.getFrame() == 0 ? FrameType.INTERACTIVE.code() : request.getFrame();
        UserFrame frame = new UserFrame();
        frame.setFrame(frameCode);
        if (request != null) {
            frame.setRequestId(request.getRequestId());
            frame.setUserId(request.getUserId());
            frame.setSessionId(request.getSessionId());
            frame.setExecutionId(request.getExecutionId());
        }
        frame.setEventType("error." + code);
        frame.setPayload(Map.of(
                "code", code,
                "message", message == null ? code : message
        ));
        frame.setTimestamp(OffsetDateTime.now().toString());
        return frame;
    }

    public static UserFrame ack(UserFrame request, String eventType, Object data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "accepted");
        payload.put("data", data);
        return response(request, eventType, payload);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asPayload(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("data", value);
        return payload;
    }

    public int getFrame() {
        return frame;
    }

    public void setFrame(int frame) {
        this.frame = frame;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }
}
