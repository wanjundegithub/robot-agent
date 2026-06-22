package robot.agent.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import robot.agent.model.RobotBinding;
import robot.agent.model.RobotBindingType;

public class RobotBindingResponse {
    private Long id;
    @JsonProperty("robot_code")
    private String robotCode;
    @JsonProperty("binding_type")
    private RobotBindingType bindingType;
    @JsonProperty("target_code")
    private String targetCode;
    private boolean enabled;
    @JsonProperty("binding_version")
    private Integer bindingVersion;

    public static RobotBindingResponse fromEntity(RobotBinding binding) {
        RobotBindingResponse response = new RobotBindingResponse();
        response.setId(binding.getId());
        response.setRobotCode(binding.getRobotCode());
        response.setBindingType(binding.getBindingType());
        response.setTargetCode(binding.getTargetCode());
        response.setEnabled(binding.isEnabled());
        response.setBindingVersion(binding.getBindingVersion());
        return response;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRobotCode() { return robotCode; }
    public void setRobotCode(String robotCode) { this.robotCode = robotCode; }
    public RobotBindingType getBindingType() { return bindingType; }
    public void setBindingType(RobotBindingType bindingType) { this.bindingType = bindingType; }
    public String getTargetCode() { return targetCode; }
    public void setTargetCode(String targetCode) { this.targetCode = targetCode; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Integer getBindingVersion() { return bindingVersion; }
    public void setBindingVersion(Integer bindingVersion) { this.bindingVersion = bindingVersion; }
}
