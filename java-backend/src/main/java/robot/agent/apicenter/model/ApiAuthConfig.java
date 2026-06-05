package robot.agent.apicenter.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "api_auth_config",
        uniqueConstraints = @UniqueConstraint(name = "uk_api_auth_scope", columnNames = {"scope_type", "scope_id"})
)
public class ApiAuthConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", length = 32, nullable = false)
    private ApiAuthScopeType scopeType;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", length = 32, nullable = false)
    private ApiAuthType authType = ApiAuthType.NO_AUTH;

    @Lob
    @Column(name = "config_ciphertext", columnDefinition = "TEXT")
    private String configCiphertext;

    @Column(name = "preview", length = 255)
    private String preview;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public ApiAuthScopeType getScopeType() {
        return scopeType;
    }

    public void setScopeType(ApiAuthScopeType scopeType) {
        this.scopeType = scopeType;
    }

    public Long getScopeId() {
        return scopeId;
    }

    public void setScopeId(Long scopeId) {
        this.scopeId = scopeId;
    }

    public ApiAuthType getAuthType() {
        return authType;
    }

    public void setAuthType(ApiAuthType authType) {
        this.authType = authType;
    }

    public String getConfigCiphertext() {
        return configCiphertext;
    }

    public void setConfigCiphertext(String configCiphertext) {
        this.configCiphertext = configCiphertext;
    }

    public String getPreview() {
        return preview;
    }

    public void setPreview(String preview) {
        this.preview = preview;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
