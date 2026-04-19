package robot.agent.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "llm_model_profile")
public class LlmModelProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "profile_code", length = 64, nullable = false, unique = true)
    private String profileCode;

    @Column(name = "provider_code", length = 64, nullable = false)
    private String providerCode;

    @Column(name = "model_code", length = 128, nullable = false)
    private String modelCode;

    @Column(name = "purpose", length = 64, nullable = false)
    private String purpose;

    @Column(name = "temperature", precision = 4, scale = 2)
    private BigDecimal temperature = BigDecimal.valueOf(0.30d);

    @Column(name = "top_p", precision = 4, scale = 2)
    private BigDecimal topP = BigDecimal.ONE;

    @Column(name = "max_tokens")
    private Integer maxTokens = 1024;

    @Column(name = "timeout_sec")
    private Integer timeoutSec = 30;

    @Column(name = "response_format", columnDefinition = "JSON")
    private String responseFormat;

    @Column(name = "fallback_profile_code", length = 64)
    private String fallbackProfileCode;

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

    public String getProfileCode() { return profileCode; }
    public void setProfileCode(String profileCode) { this.profileCode = profileCode; }

    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String providerCode) { this.providerCode = providerCode; }

    public String getModelCode() { return modelCode; }
    public void setModelCode(String modelCode) { this.modelCode = modelCode; }

    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }

    public BigDecimal getTemperature() { return temperature; }
    public void setTemperature(BigDecimal temperature) { this.temperature = temperature; }

    public BigDecimal getTopP() { return topP; }
    public void setTopP(BigDecimal topP) { this.topP = topP; }

    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }

    public Integer getTimeoutSec() { return timeoutSec; }
    public void setTimeoutSec(Integer timeoutSec) { this.timeoutSec = timeoutSec; }

    public String getResponseFormat() { return responseFormat; }
    public void setResponseFormat(String responseFormat) { this.responseFormat = responseFormat; }

    public String getFallbackProfileCode() { return fallbackProfileCode; }
    public void setFallbackProfileCode(String fallbackProfileCode) { this.fallbackProfileCode = fallbackProfileCode; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
