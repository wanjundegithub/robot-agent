package robot.agent.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
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
    @JsonProperty("user_id")
    private String userId;
    @JsonProperty("experiment_id")
    private String experimentId;
    @JsonProperty("experiment_group")
    private String experimentGroup;
    @JsonProperty("dynamic_threshold")
    private Double dynamicThreshold;
    @JsonProperty("threshold_source")
    private String thresholdSource;
    @JsonProperty("requested_tool_code")
    private String requestedToolCode;
    @JsonProperty("confirmed_tool_codes")
    private List<String> confirmedToolCodes;
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

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getExperimentId() { return experimentId; }
    public void setExperimentId(String experimentId) { this.experimentId = experimentId; }

    public String getExperimentGroup() { return experimentGroup; }
    public void setExperimentGroup(String experimentGroup) { this.experimentGroup = experimentGroup; }

    public Double getDynamicThreshold() { return dynamicThreshold; }
    public void setDynamicThreshold(Double dynamicThreshold) { this.dynamicThreshold = dynamicThreshold; }

    public String getThresholdSource() { return thresholdSource; }
    public void setThresholdSource(String thresholdSource) { this.thresholdSource = thresholdSource; }

    public String getRequestedToolCode() { return requestedToolCode; }
    public void setRequestedToolCode(String requestedToolCode) { this.requestedToolCode = requestedToolCode; }

    public List<String> getConfirmedToolCodes() { return confirmedToolCodes; }
    public void setConfirmedToolCodes(List<String> confirmedToolCodes) { this.confirmedToolCodes = confirmedToolCodes; }

    public Map<String, Object> getInputVariables() { return inputVariables; }
    public void setInputVariables(Map<String, Object> inputVariables) { this.inputVariables = inputVariables; }
}
