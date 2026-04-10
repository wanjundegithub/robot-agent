package robot.agent.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public class FormSubmitResponse {
    @JsonProperty("execution_id")
    private String executionId;
    private String status;

    public String getExecutionId() { return executionId; }
    public void setExecutionId(String executionId) { this.executionId = executionId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
