package robot.agent.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class SendMessageResponse {
    @JsonProperty("session_id")
    private String sessionId;
    @JsonProperty("execution_id")
    private String executionId;
    @JsonProperty("workflow_code")
    private String workflowCode;
    @JsonProperty("workflow_version")
    private String workflowVersion;
    private String status;
    @JsonProperty("route_decision")
    private String routeDecision;
    @JsonProperty("route_confidence")
    private Double routeConfidence;
    @JsonProperty("route_reason")
    private String routeReason;
    @JsonProperty("route_threshold")
    private Double routeThreshold;
    @JsonProperty("threshold_source")
    private String thresholdSource;
    @JsonProperty("candidate_workflows")
    private List<String> candidateWorkflows;
    @JsonProperty("active_execution_id")
    private String activeExecutionId;
    private Integer priority;
    @JsonProperty("experiment_id")
    private String experimentId;
    @JsonProperty("experiment_group")
    private String experimentGroup;
    @JsonProperty("permission_effect")
    private String permissionEffect;
    @JsonProperty("permission_reason")
    private String permissionReason;
    @JsonProperty("requested_tool_code")
    private String requestedToolCode;
    @JsonProperty("confirmation_id")
    private String confirmationId;
    @JsonProperty("confirmation_expires_at")
    private String confirmationExpiresAt;
    @JsonProperty("protection_status")
    private String protectionStatus;
    @JsonProperty("protection_reason")
    private String protectionReason;
    @JsonProperty("retry_after_seconds")
    private Long retryAfterSeconds;
    @JsonProperty("degradation_message")
    private String degradationMessage;

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getExecutionId() { return executionId; }
    public void setExecutionId(String executionId) { this.executionId = executionId; }

    public String getWorkflowCode() { return workflowCode; }
    public void setWorkflowCode(String workflowCode) { this.workflowCode = workflowCode; }

    public String getWorkflowVersion() { return workflowVersion; }
    public void setWorkflowVersion(String workflowVersion) { this.workflowVersion = workflowVersion; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getRouteDecision() { return routeDecision; }
    public void setRouteDecision(String routeDecision) { this.routeDecision = routeDecision; }

    public Double getRouteConfidence() { return routeConfidence; }
    public void setRouteConfidence(Double routeConfidence) { this.routeConfidence = routeConfidence; }

    public String getRouteReason() { return routeReason; }
    public void setRouteReason(String routeReason) { this.routeReason = routeReason; }

    public Double getRouteThreshold() { return routeThreshold; }
    public void setRouteThreshold(Double routeThreshold) { this.routeThreshold = routeThreshold; }

    public String getThresholdSource() { return thresholdSource; }
    public void setThresholdSource(String thresholdSource) { this.thresholdSource = thresholdSource; }

    public List<String> getCandidateWorkflows() { return candidateWorkflows; }
    public void setCandidateWorkflows(List<String> candidateWorkflows) { this.candidateWorkflows = candidateWorkflows; }

    public String getActiveExecutionId() { return activeExecutionId; }
    public void setActiveExecutionId(String activeExecutionId) { this.activeExecutionId = activeExecutionId; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }

    public String getExperimentId() { return experimentId; }
    public void setExperimentId(String experimentId) { this.experimentId = experimentId; }

    public String getExperimentGroup() { return experimentGroup; }
    public void setExperimentGroup(String experimentGroup) { this.experimentGroup = experimentGroup; }

    public String getPermissionEffect() { return permissionEffect; }
    public void setPermissionEffect(String permissionEffect) { this.permissionEffect = permissionEffect; }

    public String getPermissionReason() { return permissionReason; }
    public void setPermissionReason(String permissionReason) { this.permissionReason = permissionReason; }

    public String getRequestedToolCode() { return requestedToolCode; }
    public void setRequestedToolCode(String requestedToolCode) { this.requestedToolCode = requestedToolCode; }

    public String getConfirmationId() { return confirmationId; }
    public void setConfirmationId(String confirmationId) { this.confirmationId = confirmationId; }

    public String getConfirmationExpiresAt() { return confirmationExpiresAt; }
    public void setConfirmationExpiresAt(String confirmationExpiresAt) { this.confirmationExpiresAt = confirmationExpiresAt; }

    public String getProtectionStatus() { return protectionStatus; }
    public void setProtectionStatus(String protectionStatus) { this.protectionStatus = protectionStatus; }

    public String getProtectionReason() { return protectionReason; }
    public void setProtectionReason(String protectionReason) { this.protectionReason = protectionReason; }

    public Long getRetryAfterSeconds() { return retryAfterSeconds; }
    public void setRetryAfterSeconds(Long retryAfterSeconds) { this.retryAfterSeconds = retryAfterSeconds; }

    public String getDegradationMessage() { return degradationMessage; }
    public void setDegradationMessage(String degradationMessage) { this.degradationMessage = degradationMessage; }
}
