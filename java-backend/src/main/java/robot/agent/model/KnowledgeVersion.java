package robot.agent.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "knowledge_version", uniqueConstraints = {
        @UniqueConstraint(name = "uk_kb_version", columnNames = {"kb_code", "version"})
})
public class KnowledgeVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kb_code", length = 64, nullable = false)
    private String kbCode;

    @Column(name = "version", length = 32, nullable = false)
    private String version;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private KnowledgeVersionStatus status = KnowledgeVersionStatus.DRAFT;

    @Column(name = "chunk_count", nullable = false)
    private Integer chunkCount = 0;

    @Column(name = "doc_count", nullable = false)
    private Integer docCount = 0;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

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

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
