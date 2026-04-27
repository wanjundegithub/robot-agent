package robot.agent.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "capability_group_snapshot", uniqueConstraints = {
        @UniqueConstraint(name = "uk_capability_group_snapshot", columnNames = {"group_code", "snapshot_version"})
})
public class CapabilityGroupSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_code", length = 64, nullable = false)
    private String groupCode;

    @Column(name = "snapshot_version", length = 32, nullable = false)
    private String snapshotVersion;

    @Column(name = "status", length = 32, nullable = false)
    private String status = "PUBLISHED";

    @Column(name = "description", length = 500)
    private String description;

    @Lob
    @Column(name = "snapshot_payload", columnDefinition = "TEXT")
    private String snapshotPayload;

    @Column(name = "published_at")
    private LocalDateTime publishedAt = LocalDateTime.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getGroupCode() {
        return groupCode;
    }

    public void setGroupCode(String groupCode) {
        this.groupCode = groupCode;
    }

    public String getSnapshotVersion() {
        return snapshotVersion;
    }

    public void setSnapshotVersion(String snapshotVersion) {
        this.snapshotVersion = snapshotVersion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSnapshotPayload() {
        return snapshotPayload;
    }

    public void setSnapshotPayload(String snapshotPayload) {
        this.snapshotPayload = snapshotPayload;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }
}
