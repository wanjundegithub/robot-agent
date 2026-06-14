package robot.agent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "knowledge_binding")
public class KnowledgeBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", length = 32, nullable = false)
    private KnowledgeBindingScope scope;

    @Column(name = "target_id", length = 64, nullable = false)
    private String targetId;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "kb_code", length = 64, nullable = false)
    private String kbCode;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "binding_version", nullable = false)
    private Integer bindingVersion = 1;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public KnowledgeBindingScope getScope() { return scope; }
    public void setScope(KnowledgeBindingScope scope) { this.scope = scope; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public Long getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(Long workspaceId) { this.workspaceId = workspaceId; }
    public String getKbCode() { return kbCode; }
    public void setKbCode(String kbCode) { this.kbCode = kbCode; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Integer getBindingVersion() { return bindingVersion; }
    public void setBindingVersion(Integer bindingVersion) { this.bindingVersion = bindingVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
