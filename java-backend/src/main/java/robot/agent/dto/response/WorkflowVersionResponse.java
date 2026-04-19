package robot.agent.dto.response;

import robot.agent.model.Workflow;
import robot.agent.model.WorkflowVersion;
import robot.agent.model.WorkflowVersionStatus;
import java.time.LocalDateTime;

public class WorkflowVersionResponse {
    private Long id;
    private Long workflowId;
    private String workflowCode;
    private String workflowName;
    private String version;
    private WorkflowVersionStatus status;
    private String definition;
    private String entryRule;
    private String editorMeta;
    private String config;
    private LocalDateTime createdAt;
    private LocalDateTime publishedAt;

    public static WorkflowVersionResponse fromEntity(WorkflowVersion version) {
        return fromEntity(version, null);
    }

    public static WorkflowVersionResponse fromEntity(WorkflowVersion version, Workflow workflow) {
        WorkflowVersionResponse response = new WorkflowVersionResponse();
        response.setId(version.getId());
        response.setWorkflowCode(version.getWorkflowCode());
        response.setWorkflowId(workflow != null ? workflow.getId() : null);
        response.setWorkflowName(workflow != null ? workflow.getName() : null);
        response.setVersion(version.getVersion());
        response.setStatus(version.getStatus());
        response.setDefinition(version.getDefinition());
        response.setEntryRule(version.getEntryRule());
        response.setEditorMeta(version.getEditorMeta());
        response.setConfig(version.getConfig());
        response.setCreatedAt(version.getCreatedAt());
        response.setPublishedAt(version.getPublishedAt());
        return response;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getWorkflowId() { return workflowId; }
    public void setWorkflowId(Long workflowId) { this.workflowId = workflowId; }

    public String getWorkflowCode() { return workflowCode; }
    public void setWorkflowCode(String workflowCode) { this.workflowCode = workflowCode; }

    public String getWorkflowName() { return workflowName; }
    public void setWorkflowName(String workflowName) { this.workflowName = workflowName; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public WorkflowVersionStatus getStatus() { return status; }
    public void setStatus(WorkflowVersionStatus status) { this.status = status; }

    public String getDefinition() { return definition; }
    public void setDefinition(String definition) { this.definition = definition; }

    public String getEntryRule() { return entryRule; }
    public void setEntryRule(String entryRule) { this.entryRule = entryRule; }

    public String getEditorMeta() { return editorMeta; }
    public void setEditorMeta(String editorMeta) { this.editorMeta = editorMeta; }

    public String getConfig() { return config; }
    public void setConfig(String config) { this.config = config; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
}
