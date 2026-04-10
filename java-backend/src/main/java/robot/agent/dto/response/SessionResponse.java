package robot.agent.dto.response;

import robot.agent.model.Session;
import robot.agent.model.SessionStatus;
import java.time.LocalDateTime;

public class SessionResponse {
    private String id;
    private Long workspaceId;
    private String userId;
    private SessionStatus status;
    private String currentExecutionId;
    private String variables;
    private LocalDateTime createdAt;
    private LocalDateTime lastActivityAt;

    public static SessionResponse fromEntity(Session session) {
        SessionResponse response = new SessionResponse();
        response.setId(session.getId());
        response.setWorkspaceId(session.getWorkspaceId());
        response.setUserId(session.getUserId());
        response.setStatus(session.getStatus());
        response.setCurrentExecutionId(session.getCurrentExecutionId());
        response.setVariables(session.getVariables());
        response.setCreatedAt(session.getCreatedAt());
        response.setLastActivityAt(session.getLastActivityAt());
        return response;
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

    public String getVariables() { return variables; }
    public void setVariables(String variables) { this.variables = variables; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getLastActivityAt() { return lastActivityAt; }
    public void setLastActivityAt(LocalDateTime lastActivityAt) { this.lastActivityAt = lastActivityAt; }
}
