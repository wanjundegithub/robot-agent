package robot.agent.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "knowledge_document")
public class KnowledgeDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kb_code", length = 64, nullable = false)
    private String kbCode;

    @Column(name = "version", length = 32, nullable = false)
    private String version;

    @Column(name = "doc_id", length = 64, nullable = false)
    private String docId;

    @Column(name = "title", length = 256)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "filename", length = 256)
    private String filename;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "file_url", length = 512)
    private String fileUrl;

    @Column(name = "source_type", length = 32)
    private String sourceType;

    @Column(name = "raw_content", columnDefinition = "TEXT")
    private String rawContent;

    @Column(name = "raw_bucket", length = 128)
    private String rawBucket;

    @Column(name = "raw_object_key", length = 512)
    private String rawObjectKey;

    @Column(name = "raw_etag", length = 128)
    private String rawEtag;

    @Column(name = "raw_content_type", length = 128)
    private String rawContentType;

    @Column(name = "extracted_object_key", length = 512)
    private String extractedObjectKey;

    @Column(name = "content_hash", length = 128)
    private String contentHash;

    @Column(name = "generated_title", length = 256)
    private String generatedTitle;

    @Column(name = "generated_summary", columnDefinition = "TEXT")
    private String generatedSummary;

    @Column(name = "generated_keywords", columnDefinition = "TEXT")
    private String generatedKeywords;

    @Column(name = "index_version")
    private Integer indexVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", columnDefinition = "VARCHAR(20)", nullable = false)
    private KnowledgeDocumentStatus status = KnowledgeDocumentStatus.PENDING;

    @Column(name = "chunk_count")
    private Integer chunkCount;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

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

    public String getDocId() {
        return docId;
    }

    public void setDocId(String docId) {
        this.docId = docId;
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

    public String getFileUrl() {
        return fileUrl;
    }

    public void setFileUrl(String fileUrl) {
        this.fileUrl = fileUrl;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getRawContent() {
        return rawContent;
    }

    public void setRawContent(String rawContent) {
        this.rawContent = rawContent;
    }

    public String getRawBucket() {
        return rawBucket;
    }

    public void setRawBucket(String rawBucket) {
        this.rawBucket = rawBucket;
    }

    public String getRawObjectKey() {
        return rawObjectKey;
    }

    public void setRawObjectKey(String rawObjectKey) {
        this.rawObjectKey = rawObjectKey;
    }

    public String getRawEtag() {
        return rawEtag;
    }

    public void setRawEtag(String rawEtag) {
        this.rawEtag = rawEtag;
    }

    public String getRawContentType() {
        return rawContentType;
    }

    public void setRawContentType(String rawContentType) {
        this.rawContentType = rawContentType;
    }

    public String getExtractedObjectKey() {
        return extractedObjectKey;
    }

    public void setExtractedObjectKey(String extractedObjectKey) {
        this.extractedObjectKey = extractedObjectKey;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
