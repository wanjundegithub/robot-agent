package robot.agent.dto.response;

import robot.agent.model.KnowledgeDocument;
import robot.agent.model.KnowledgeDocumentStatus;

import java.time.LocalDateTime;

public class KnowledgeDocumentResponse {
    private String docId;
    private String kbCode;
    private String title;
    private String description;
    private String filename;
    private Long fileSize;
    private String sourceType;
    private KnowledgeDocumentStatus status;
    private Integer chunkCount;
    private String errorMessage;
    private String generatedTitle;
    private String generatedSummary;
    private String generatedKeywords;
    private Integer indexVersion;
    private LocalDateTime uploadedAt;
    private LocalDateTime processedAt;

    public static KnowledgeDocumentResponse fromEntity(KnowledgeDocument entity) {
        KnowledgeDocumentResponse response = new KnowledgeDocumentResponse();
        response.setDocId(entity.getDocId());
        response.setKbCode(entity.getKbCode());
        response.setTitle(entity.getTitle());
        response.setDescription(entity.getDescription());
        response.setFilename(entity.getFilename());
        response.setFileSize(entity.getFileSize());
        response.setSourceType(entity.getSourceType());
        response.setStatus(entity.getStatus());
        response.setChunkCount(entity.getChunkCount());
        response.setErrorMessage(entity.getErrorMessage());
        response.setGeneratedTitle(entity.getGeneratedTitle());
        response.setGeneratedSummary(entity.getGeneratedSummary());
        response.setGeneratedKeywords(entity.getGeneratedKeywords());
        response.setIndexVersion(entity.getIndexVersion());
        response.setUploadedAt(entity.getUploadedAt());
        response.setProcessedAt(entity.getProcessedAt());
        return response;
    }

    public String getDocId() {
        return docId;
    }

    public void setDocId(String docId) {
        this.docId = docId;
    }

    public String getKbCode() {
        return kbCode;
    }

    public void setKbCode(String kbCode) {
        this.kbCode = kbCode;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public KnowledgeDocumentStatus getStatus() {
        return status;
    }

    public void setStatus(KnowledgeDocumentStatus status) {
        this.status = status;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(Integer chunkCount) {
        this.chunkCount = chunkCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getGeneratedTitle() {
        return generatedTitle;
    }

    public void setGeneratedTitle(String generatedTitle) {
        this.generatedTitle = generatedTitle;
    }

    public String getGeneratedSummary() {
        return generatedSummary;
    }

    public void setGeneratedSummary(String generatedSummary) {
        this.generatedSummary = generatedSummary;
    }

    public String getGeneratedKeywords() {
        return generatedKeywords;
    }

    public void setGeneratedKeywords(String generatedKeywords) {
        this.generatedKeywords = generatedKeywords;
    }

    public Integer getIndexVersion() {
        return indexVersion;
    }

    public void setIndexVersion(Integer indexVersion) {
        this.indexVersion = indexVersion;
    }

    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(LocalDateTime uploadedAt) {
        this.uploadedAt = uploadedAt;
    }

    public LocalDateTime getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(LocalDateTime processedAt) {
        this.processedAt = processedAt;
    }
}
