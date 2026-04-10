package robot.agent.dto.request;

public class CreateSessionRequest {
    private String userId;
    private Long workspaceId;
    private String variables;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public Long getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(Long workspaceId) { this.workspaceId = workspaceId; }

    public String getVariables() { return variables; }
    public void setVariables(String variables) { this.variables = variables; }
}
