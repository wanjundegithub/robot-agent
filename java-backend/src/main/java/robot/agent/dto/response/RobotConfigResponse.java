package robot.agent.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import robot.agent.model.RobotBinding;
import robot.agent.model.RobotBindingType;
import robot.agent.model.RobotConfig;
import robot.agent.model.RobotStatus;

import java.util.List;

public class RobotConfigResponse {
    private Long id;
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
    @JsonProperty("workflow_binding_count")
    private long workflowBindingCount;
    @JsonProperty("knowledge_binding_count")
    private long knowledgeBindingCount;

    public static RobotConfigResponse fromEntity(RobotConfig robot, List<RobotBinding> bindings) {
        RobotConfigResponse response = new RobotConfigResponse();
        response.setId(robot.getId());
        response.setWorkspaceId(robot.getWorkspaceId());
        response.setRobotCode(robot.getRobotCode());
        response.setName(robot.getName());
        response.setDescription(robot.getDescription());
        response.setAvatar(robot.getAvatar());
        response.setOpeningMessage(robot.getOpeningMessage());
        response.setStatus(robot.getStatus());
        response.setDefaultModelCode(robot.getDefaultModelCode());
        response.setRouteStrategy(robot.getRouteStrategy());
        List<RobotBinding> safeBindings = bindings == null ? List.of() : bindings;
        response.setWorkflowBindingCount(safeBindings.stream().filter(binding -> binding.getBindingType() == RobotBindingType.WORKFLOW_SPACE).count());
        response.setKnowledgeBindingCount(safeBindings.stream().filter(binding -> binding.getBindingType() == RobotBindingType.KNOWLEDGE_SPACE).count());
        return response;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
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
    public long getWorkflowBindingCount() { return workflowBindingCount; }
    public void setWorkflowBindingCount(long workflowBindingCount) { this.workflowBindingCount = workflowBindingCount; }
    public long getKnowledgeBindingCount() { return knowledgeBindingCount; }
    public void setKnowledgeBindingCount(long knowledgeBindingCount) { this.knowledgeBindingCount = knowledgeBindingCount; }
}
