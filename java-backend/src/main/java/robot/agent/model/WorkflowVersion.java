package robot.agent.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "workflow_version", uniqueConstraints = {
    @UniqueConstraint(name = "uk_workflow_version", columnNames = {"workflow_code", "version"})
})
public class WorkflowVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_code", length = 64, nullable = false)
    private String workflowCode;

    @Column(name = "version", length = 32, nullable = false)
    private String version;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private WorkflowVersionStatus status = WorkflowVersionStatus.DRAFT;

    @Column(name = "definition", columnDefinition = "JSON", nullable = false)
    private String definition;

    @Column(name = "entry_rule", columnDefinition = "JSON")
    private String entryRule;

    @Column(name = "editor_meta", columnDefinition = "JSON")
    private String editorMeta;

    @Column(name = "config", columnDefinition = "JSON")
    private String config;

    @Column(name = "workflow_snapshot", columnDefinition = "JSON")
    private String workflowSnapshot;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

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

    public String getEditorMeta() { return editorMeta; }
    public void setEditorMeta(String editorMeta) { this.editorMeta = editorMeta; }

    public String getConfig() { return config; }
    public void setConfig(String config) { this.config = config; }

    public String getWorkflowSnapshot() { return workflowSnapshot; }
    public void setWorkflowSnapshot(String workflowSnapshot) { this.workflowSnapshot = workflowSnapshot; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
