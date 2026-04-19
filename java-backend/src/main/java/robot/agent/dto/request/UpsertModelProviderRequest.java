package robot.agent.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

public class UpsertModelProviderRequest {
    @JsonProperty("provider_code")
    private String providerCode;
    @JsonProperty("provider_name")
    private String providerName;
    @JsonProperty("provider_type")
    private String providerType;
    @JsonProperty("base_url")
    private String baseUrl;
    @JsonProperty("default_model_code")
    private String defaultModelCode;
    @JsonProperty("api_key_secret_ref")
    private String apiKeySecretRef;
    private Boolean enabled;

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

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
