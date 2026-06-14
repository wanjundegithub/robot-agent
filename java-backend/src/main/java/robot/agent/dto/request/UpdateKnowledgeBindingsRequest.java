package robot.agent.dto.request;

import robot.agent.model.KnowledgeBindingScope;

import java.util.ArrayList;
import java.util.List;

public class UpdateKnowledgeBindingsRequest {
    private KnowledgeBindingScope scope;
    private String targetId;
    private Long workspaceId;
    private List<String> kbCodes = new ArrayList<>();

    public KnowledgeBindingScope getScope() { return scope; }
    public void setScope(KnowledgeBindingScope scope) { this.scope = scope; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public Long getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(Long workspaceId) { this.workspaceId = workspaceId; }
    public List<String> getKbCodes() { return kbCodes; }
    public void setKbCodes(List<String> kbCodes) { this.kbCodes = kbCodes == null ? new ArrayList<>() : kbCodes; }
}
