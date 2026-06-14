package robot.agent.dto.response;

import robot.agent.model.KnowledgeBinding;
import robot.agent.model.KnowledgeBindingScope;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class KnowledgeBindingResponse {
    private KnowledgeBindingScope scope;
    private String targetId;
    private Long workspaceId;
    private List<String> kbCodes = new ArrayList<>();
    private Integer bindingVersion;
    private LocalDateTime updatedAt;

    public static KnowledgeBindingResponse fromBindings(List<KnowledgeBinding> bindings) {
        KnowledgeBindingResponse response = new KnowledgeBindingResponse();
        if (bindings == null || bindings.isEmpty()) {
            return response;
        }
        KnowledgeBinding first = bindings.get(0);
        response.setScope(first.getScope());
        response.setTargetId(first.getTargetId());
        response.setWorkspaceId(first.getWorkspaceId());
        response.setBindingVersion(first.getBindingVersion());
        response.setUpdatedAt(first.getUpdatedAt());
        response.setKbCodes(bindings.stream().map(KnowledgeBinding::getKbCode).toList());
        return response;
    }

    public KnowledgeBindingScope getScope() { return scope; }
    public void setScope(KnowledgeBindingScope scope) { this.scope = scope; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public Long getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(Long workspaceId) { this.workspaceId = workspaceId; }
    public List<String> getKbCodes() { return kbCodes; }
    public void setKbCodes(List<String> kbCodes) { this.kbCodes = kbCodes == null ? new ArrayList<>() : kbCodes; }
    public Integer getBindingVersion() { return bindingVersion; }
    public void setBindingVersion(Integer bindingVersion) { this.bindingVersion = bindingVersion; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
