package robot.agent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.LocalDateTime;

@Entity
@Table(name = "llm_model_record")
public class LlmModelRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "model_code", length = 64, nullable = false, unique = true)
    private String modelCode;

    @Column(name = "model_name", length = 128, nullable = false)
    private String modelName;

    @Column(name = "provider_code", length = 64, nullable = false)
    private String providerCode;

    @Column(name = "upstream_model_code", length = 128)
    private String upstreamModelCode;

    @Column(name = "capabilities_json", columnDefinition = "JSON")
    private String capabilitiesJson;

    @Column(name = "default_system_prompt", columnDefinition = "TEXT")
    private String defaultSystemPrompt;

    @Column(name = "default_options_json", columnDefinition = "JSON")
    private String defaultOptionsJson;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Transient
    private String providerType;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getModelCode() { return modelCode; }
    public void setModelCode(String modelCode) { this.modelCode = modelCode; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String providerCode) { this.providerCode = providerCode; }

    public String getUpstreamModelCode() { return upstreamModelCode; }
    public void setUpstreamModelCode(String upstreamModelCode) { this.upstreamModelCode = upstreamModelCode; }

    public String getCapabilitiesJson() { return capabilitiesJson; }
    public void setCapabilitiesJson(String capabilitiesJson) { this.capabilitiesJson = capabilitiesJson; }

    public String getDefaultSystemPrompt() { return defaultSystemPrompt; }
    public void setDefaultSystemPrompt(String defaultSystemPrompt) { this.defaultSystemPrompt = defaultSystemPrompt; }

    public String getDefaultOptionsJson() { return defaultOptionsJson; }
    public void setDefaultOptionsJson(String defaultOptionsJson) { this.defaultOptionsJson = defaultOptionsJson; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getProviderType() { return providerType; }
    public void setProviderType(String providerType) { this.providerType = providerType; }

    public String getProviderModelCode() { return upstreamModelCode; }
    public void setProviderModelCode(String providerModelCode) { this.upstreamModelCode = providerModelCode; }
}
