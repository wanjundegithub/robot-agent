package robot.agent.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ResumeExecutionResponse {
    @JsonProperty("execution_id")
    private String executionId;
    private String status;
    @JsonProperty("form_definition")
    private String formDefinition;

    public String getExecutionId() { return executionId; }
    public void setExecutionId(String executionId) { this.executionId = executionId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getFormDefinition() { return formDefinition; }
    public void setFormDefinition(String formDefinition) { this.formDefinition = formDefinition; }
}
