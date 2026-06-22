package robot.agent.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

public class CreateWorkflowRequest {
    @JsonProperty("workflow_code")
    private String workflowCode;
    @JsonProperty("workflow_space_code")
    private String workflowSpaceCode;
    private String name;
    private String description;
    @JsonProperty("workspace_id")
    private Long workspaceId;

    public String getWorkflowCode() { return workflowCode; }
    public void setWorkflowCode(String workflowCode) { this.workflowCode = workflowCode; }

    public String getWorkflowSpaceCode() { return workflowSpaceCode; }
    public void setWorkflowSpaceCode(String workflowSpaceCode) { this.workflowSpaceCode = workflowSpaceCode; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Long getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(Long workspaceId) { this.workspaceId = workspaceId; }
}
