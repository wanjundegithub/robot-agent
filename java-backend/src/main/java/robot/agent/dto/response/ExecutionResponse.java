package robot.agent.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import robot.agent.model.Execution;

public class ExecutionResponse {
    @JsonProperty("execution_id")
    private String executionId;
    @JsonProperty("session_id")
    private String sessionId;
    @JsonProperty("workflow_code")
    private String workflowCode;
    @JsonProperty("workflow_version")
    private String workflowVersion;
    private String status;
    @JsonProperty("current_node_id")
    private String currentNodeId;
    private String variables;
    private String error;

    public static ExecutionResponse fromEntity(Execution execution) {
        ExecutionResponse response = new ExecutionResponse();
        response.setExecutionId(execution.getId());
        response.setSessionId(execution.getSessionId());
        response.setWorkflowCode(execution.getWorkflowCode());
        response.setWorkflowVersion(execution.getWorkflowVersion());
        response.setStatus(execution.getStatus() == null ? null : execution.getStatus().getValue());
        response.setCurrentNodeId(execution.getCurrentNodeId());
        response.setVariables(execution.getVariables());
        response.setError(execution.getError());
        return response;
    }

    public String getExecutionId() { return executionId; }
    public void setExecutionId(String executionId) { this.executionId = executionId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getWorkflowCode() { return workflowCode; }
    public void setWorkflowCode(String workflowCode) { this.workflowCode = workflowCode; }

    public String getWorkflowVersion() { return workflowVersion; }
    public void setWorkflowVersion(String workflowVersion) { this.workflowVersion = workflowVersion; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCurrentNodeId() { return currentNodeId; }
    public void setCurrentNodeId(String currentNodeId) { this.currentNodeId = currentNodeId; }

    public String getVariables() { return variables; }
    public void setVariables(String variables) { this.variables = variables; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
