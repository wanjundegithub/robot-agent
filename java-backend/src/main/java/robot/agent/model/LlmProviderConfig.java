package robot.agent.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "llm_provider_config")
public class LlmProviderConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_code", length = 64, nullable = false, unique = true)
    private String providerCode;

    @Column(name = "provider_name", length = 128)
    private String providerName;

    @Column(name = "provider_type", length = 64, nullable = false)
    private String providerType;

    @Column(name = "base_url", length = 256, nullable = false)
    private String baseUrl;

    @Transient
    private String defaultModelCode;

    @Column(name = "api_key_secret_ref", length = 256)
    private String apiKeySecretRef;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String providerCode) { this.providerCode = providerCode; }

    public String getProviderName() { return providerName; }
    public void setProviderName(String providerName) { this.providerName = providerName; }

    public String getProviderType() { return providerType; }
    public void setProviderType(String providerType) { this.providerType = providerType; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getDefaultModelCode() { return defaultModelCode; }
    public void setDefaultModelCode(String defaultModelCode) { this.defaultModelCode = defaultModelCode; }

    public String getApiKeySecretRef() { return apiKeySecretRef; }
    public void setApiKeySecretRef(String apiKeySecretRef) { this.apiKeySecretRef = apiKeySecretRef; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
