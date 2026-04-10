package robot.agent.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "session")
public class Session {
    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private SessionStatus status = SessionStatus.ACTIVE;

    @Column(name = "current_execution_id", length = 64)
    private String currentExecutionId;

    @Column(name = "suspended_stack", columnDefinition = "JSON")
    private String suspendedStack;

    @Column(name = "variables", columnDefinition = "JSON")
    private String variables;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "last_activity_at")
    private LocalDateTime lastActivityAt = LocalDateTime.now();

    public Session() {}

    public Session(String id, Long workspaceId, String userId) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.userId = userId;
        this.status = SessionStatus.ACTIVE;
        this.expiresAt = LocalDateTime.now().plusHours(24);
        this.createdAt = LocalDateTime.now();
        this.lastActivityAt = LocalDateTime.now();
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Long getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(Long workspaceId) { this.workspaceId = workspaceId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public SessionStatus getStatus() { return status; }
    public void setStatus(SessionStatus status) { this.status = status; }

    public String getCurrentExecutionId() { return currentExecutionId; }
    public void setCurrentExecutionId(String currentExecutionId) { this.currentExecutionId = currentExecutionId; }

    public String getSuspendedStack() { return suspendedStack; }
    public void setSuspendedStack(String suspendedStack) { this.suspendedStack = suspendedStack; }

    public String getVariables() { return variables; }
    public void setVariables(String variables) { this.variables = variables; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getLastActivityAt() { return lastActivityAt; }
    public void setLastActivityAt(LocalDateTime lastActivityAt) { this.lastActivityAt = lastActivityAt; }
}
