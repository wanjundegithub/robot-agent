package robot.agent.dto.response;

import robot.agent.model.KnowledgeBase;
import robot.agent.model.KnowledgeBaseStatus;

import java.time.LocalDateTime;

public class KnowledgeBaseResponse {
    private Long id;
    private Long workspaceId;
    private String kbCode;
    private String name;
    private String description;
    private String embeddingModel;
    private String currentVersion;
    private KnowledgeBaseStatus status;
    private LocalDateTime createdAt;

    public static KnowledgeBaseResponse fromEntity(KnowledgeBase knowledgeBase) {
        KnowledgeBaseResponse response = new KnowledgeBaseResponse();
        response.setId(knowledgeBase.getId());
        response.setWorkspaceId(knowledgeBase.getWorkspaceId());
        response.setKbCode(knowledgeBase.getKbCode());
        response.setName(knowledgeBase.getName());
        response.setDescription(knowledgeBase.getDescription());
        response.setEmbeddingModel(knowledgeBase.getEmbeddingModel());
        response.setCurrentVersion(knowledgeBase.getCurrentVersion());
        response.setStatus(knowledgeBase.getStatus());
        response.setCreatedAt(knowledgeBase.getCreatedAt());
        return response;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(Long workspaceId) {
        this.workspaceId = workspaceId;
    }

    public String getKbCode() {
        return kbCode;
    }

    public void setKbCode(String kbCode) {
        this.kbCode = kbCode;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(String currentVersion) {
        this.currentVersion = currentVersion;
    }

    public KnowledgeBaseStatus getStatus() {
        return status;
    }

    public void setStatus(KnowledgeBaseStatus status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
