package robot.agent.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public class ExecuteRequest {
    @JsonProperty("session_id")
    private String sessionId;
    @JsonProperty("execution_id")
    private String executionId;
    @JsonProperty("workflow_code")
    private String workflowCode;
    @JsonProperty("workflow_version")
    private String workflowVersion;
    @JsonProperty("message_id")
    private String messageId;
    private int priority;
    @JsonProperty("route_decision")
    private String routeDecision;
    @JsonProperty("route_reason")
    private String routeReason;
    @JsonProperty("route_confidence")
    private double routeConfidence;
    @JsonProperty("input_variables")
    private Map<String, Object> inputVariables;

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getExecutionId() { return executionId; }
    public void setExecutionId(String executionId) { this.executionId = executionId; }

    public String getWorkflowCode() { return workflowCode; }
    public void setWorkflowCode(String workflowCode) { this.workflowCode = workflowCode; }

    public String getWorkflowVersion() { return workflowVersion; }
    public void setWorkflowVersion(String workflowVersion) { this.workflowVersion = workflowVersion; }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public String getRouteDecision() { return routeDecision; }
    public void setRouteDecision(String routeDecision) { this.routeDecision = routeDecision; }

    public String getRouteReason() { return routeReason; }
    public void setRouteReason(String routeReason) { this.routeReason = routeReason; }

    public double getRouteConfidence() { return routeConfidence; }
    public void setRouteConfidence(double routeConfidence) { this.routeConfidence = routeConfidence; }

    public Map<String, Object> getInputVariables() { return inputVariables; }
    public void setInputVariables(Map<String, Object> inputVariables) { this.inputVariables = inputVariables; }
}
