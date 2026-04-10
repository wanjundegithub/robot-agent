package robot.agent.dto.response;

import robot.agent.model.KnowledgeVersion;
import robot.agent.model.KnowledgeVersionStatus;

import java.time.LocalDateTime;

public class KnowledgeVersionResponse {
    private Long id;
    private String kbCode;
    private String version;
    private KnowledgeVersionStatus status;
    private Integer chunkCount;
    private Integer docCount;
    private LocalDateTime createdAt;
    private LocalDateTime publishedAt;

    public static KnowledgeVersionResponse fromEntity(KnowledgeVersion knowledgeVersion) {
        KnowledgeVersionResponse response = new KnowledgeVersionResponse();
        response.setId(knowledgeVersion.getId());
        response.setKbCode(knowledgeVersion.getKbCode());
        response.setVersion(knowledgeVersion.getVersion());
        response.setStatus(knowledgeVersion.getStatus());
        response.setChunkCount(knowledgeVersion.getChunkCount());
        response.setDocCount(knowledgeVersion.getDocCount());
        response.setCreatedAt(knowledgeVersion.getCreatedAt());
        response.setPublishedAt(knowledgeVersion.getPublishedAt());
        return response;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getKbCode() {
        return kbCode;
    }

    public void setKbCode(String kbCode) {
        this.kbCode = kbCode;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public KnowledgeVersionStatus getStatus() {
        return status;
    }

    public void setStatus(KnowledgeVersionStatus status) {
        this.status = status;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(Integer chunkCount) {
        this.chunkCount = chunkCount;
    }

    public Integer getDocCount() {
        return docCount;
    }

    public void setDocCount(Integer docCount) {
        this.docCount = docCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }
}
