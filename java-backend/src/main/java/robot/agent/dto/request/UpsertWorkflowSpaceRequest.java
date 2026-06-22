package robot.agent.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

public class UpsertWorkflowSpaceRequest {
    @JsonProperty("workspace_id")
    private Long workspaceId;
    @JsonProperty("space_code")
    private String spaceCode;
    private String name;
    private String description;
    @JsonProperty("created_by")
    private String createdBy;

    public Long getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(Long workspaceId) { this.workspaceId = workspaceId; }
    public String getSpaceCode() { return spaceCode; }
    public void setSpaceCode(String spaceCode) { this.spaceCode = spaceCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
