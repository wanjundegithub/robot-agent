package robot.agent.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import robot.agent.model.WorkflowSpace;
import robot.agent.model.WorkflowStatus;

import java.time.LocalDateTime;

public class WorkflowSpaceResponse {
    private Long id;
    @JsonProperty("workspace_id")
    private Long workspaceId;
    @JsonProperty("space_code")
    private String spaceCode;
    private String name;
    private String description;
    private WorkflowStatus status;
    @JsonProperty("created_by")
    private String createdBy;
    @JsonProperty("created_at")
    private LocalDateTime createdAt;
    @JsonProperty("updated_at")
    private LocalDateTime updatedAt;

    public static WorkflowSpaceResponse fromEntity(WorkflowSpace space) {
        WorkflowSpaceResponse response = new WorkflowSpaceResponse();
        response.setId(space.getId());
        response.setWorkspaceId(space.getWorkspaceId());
        response.setSpaceCode(space.getSpaceCode());
        response.setName(space.getName());
        response.setDescription(space.getDescription());
        response.setStatus(space.getStatus());
        response.setCreatedBy(space.getCreatedBy());
        response.setCreatedAt(space.getCreatedAt());
        response.setUpdatedAt(space.getUpdatedAt());
        return response;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(Long workspaceId) { this.workspaceId = workspaceId; }
    public String getSpaceCode() { return spaceCode; }
    public void setSpaceCode(String spaceCode) { this.spaceCode = spaceCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public WorkflowStatus getStatus() { return status; }
    public void setStatus(WorkflowStatus status) { this.status = status; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
