package robot.agent.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import robot.agent.model.RobotStatus;

public class UpsertRobotConfigRequest {
    @JsonProperty("workspace_id")
    private Long workspaceId;
    @JsonProperty("robot_code")
    private String robotCode;
    private String name;
    private String description;
    private String avatar;
    @JsonProperty("opening_message")
    private String openingMessage;
    private RobotStatus status;
    @JsonProperty("default_model_code")
    private String defaultModelCode;
    @JsonProperty("route_strategy")
    private String routeStrategy;
    @JsonProperty("created_by")
    private String createdBy;

    public Long getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(Long workspaceId) { this.workspaceId = workspaceId; }
    public String getRobotCode() { return robotCode; }
    public void setRobotCode(String robotCode) { this.robotCode = robotCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public String getOpeningMessage() { return openingMessage; }
    public void setOpeningMessage(String openingMessage) { this.openingMessage = openingMessage; }
    public RobotStatus getStatus() { return status; }
    public void setStatus(RobotStatus status) { this.status = status; }
    public String getDefaultModelCode() { return defaultModelCode; }
    public void setDefaultModelCode(String defaultModelCode) { this.defaultModelCode = defaultModelCode; }
    public String getRouteStrategy() { return routeStrategy; }
    public void setRouteStrategy(String routeStrategy) { this.routeStrategy = routeStrategy; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
