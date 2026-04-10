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
    @JsonProperty("candidate_workflows")
    private List<String> candidateWorkflows;
    @JsonProperty("active_execution_id")
    private String activeExecutionId;
    private Integer priority;

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

    public List<String> getCandidateWorkflows() { return candidateWorkflows; }
    public void setCandidateWorkflows(List<String> candidateWorkflows) { this.candidateWorkflows = candidateWorkflows; }

    public String getActiveExecutionId() { return activeExecutionId; }
    public void setActiveExecutionId(String activeExecutionId) { this.activeExecutionId = activeExecutionId; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
}
