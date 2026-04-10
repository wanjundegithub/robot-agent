package robot.agent.dto.response;

import robot.agent.model.WorkflowVersion;
import robot.agent.model.WorkflowVersionStatus;
import java.time.LocalDateTime;

public class WorkflowVersionResponse {
    private Long id;
    private String workflowCode;
    private String version;
    private WorkflowVersionStatus status;
    private String definition;
    private String entryRule;
    private String config;
    private LocalDateTime createdAt;
    private LocalDateTime publishedAt;

    public static WorkflowVersionResponse fromEntity(WorkflowVersion version) {
        WorkflowVersionResponse response = new WorkflowVersionResponse();
        response.setId(version.getId());
        response.setWorkflowCode(version.getWorkflowCode());
        response.setVersion(version.getVersion());
        response.setStatus(version.getStatus());
        response.setDefinition(version.getDefinition());
        response.setEntryRule(version.getEntryRule());
        response.setConfig(version.getConfig());
        response.setCreatedAt(version.getCreatedAt());
        response.setPublishedAt(version.getPublishedAt());
        return response;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getWorkflowCode() { return workflowCode; }
    public void setWorkflowCode(String workflowCode) { this.workflowCode = workflowCode; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public WorkflowVersionStatus getStatus() { return status; }
    public void setStatus(WorkflowVersionStatus status) { this.status = status; }

    public String getDefinition() { return definition; }
    public void setDefinition(String definition) { this.definition = definition; }

    public String getEntryRule() { return entryRule; }
    public void setEntryRule(String entryRule) { this.entryRule = entryRule; }

    public String getConfig() { return config; }
    public void setConfig(String config) { this.config = config; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
}
