package robot.agent.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

public class UpdateRobotBindingsRequest {
    @JsonProperty("workspace_id")
    private Long workspaceId;
    @JsonProperty("workflow_space_codes")
    private List<String> workflowSpaceCodes = new ArrayList<>();
    @JsonProperty("kb_codes")
    private List<String> kbCodes = new ArrayList<>();

    public Long getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(Long workspaceId) { this.workspaceId = workspaceId; }
    public List<String> getWorkflowSpaceCodes() { return workflowSpaceCodes; }
    public void setWorkflowSpaceCodes(List<String> workflowSpaceCodes) {
        this.workflowSpaceCodes = workflowSpaceCodes == null ? new ArrayList<>() : workflowSpaceCodes;
    }
    public List<String> getKbCodes() { return kbCodes; }
    public void setKbCodes(List<String> kbCodes) { this.kbCodes = kbCodes == null ? new ArrayList<>() : kbCodes; }
}
