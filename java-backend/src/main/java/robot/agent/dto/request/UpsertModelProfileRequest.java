package robot.agent.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.Map;

public class UpsertModelProfileRequest {
    @JsonProperty("profile_code")
    private String profileCode;
    @JsonProperty("provider_code")
    private String providerCode;
    @JsonProperty("model_code")
    private String modelCode;
    private String purpose;
    private BigDecimal temperature;
    @JsonProperty("top_p")
    private BigDecimal topP;
    @JsonProperty("max_tokens")
    private Integer maxTokens;
    @JsonProperty("timeout_sec")
    private Integer timeoutSec;
    @JsonProperty("response_format")
    private Map<String, Object> responseFormat;
    @JsonProperty("fallback_profile_code")
    private String fallbackProfileCode;
    private Boolean enabled;

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

    public Map<String, Object> getResponseFormat() { return responseFormat; }
    public void setResponseFormat(Map<String, Object> responseFormat) { this.responseFormat = responseFormat; }

    public String getFallbackProfileCode() { return fallbackProfileCode; }
    public void setFallbackProfileCode(String fallbackProfileCode) { this.fallbackProfileCode = fallbackProfileCode; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
