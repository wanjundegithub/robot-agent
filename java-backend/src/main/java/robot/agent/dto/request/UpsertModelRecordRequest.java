package robot.agent.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

public class UpsertModelRecordRequest {
    @JsonProperty("custom_model_name")
    private String customModelName;
    private String provider;
    @JsonProperty("model_name")
    private String modelName;
    @JsonProperty("api_key")
    private String apiKey;
    @JsonProperty("base_url")
    private String baseUrl;
    @JsonProperty("model_code")
    private String modelCode;
    @JsonProperty("provider_code")
    private String providerCode;
    @JsonProperty("upstream_model_code")
    private String upstreamModelCode;
    private Object capabilities;
    @JsonProperty("default_system_prompt")
    private String defaultSystemPrompt;
    @JsonProperty("default_options")
    private Object defaultOptions;
    private Boolean enabled;

    public String getCustomModelName() { return customModelName; }
    public void setCustomModelName(String customModelName) { this.customModelName = customModelName; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getModelCode() { return modelCode; }
    public void setModelCode(String modelCode) { this.modelCode = modelCode; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String providerCode) { this.providerCode = providerCode; }

    public String getUpstreamModelCode() { return upstreamModelCode; }
    public void setUpstreamModelCode(String upstreamModelCode) { this.upstreamModelCode = upstreamModelCode; }

    public Object getCapabilities() { return capabilities; }
    public void setCapabilities(Object capabilities) { this.capabilities = capabilities; }

    public String getDefaultSystemPrompt() { return defaultSystemPrompt; }
    public void setDefaultSystemPrompt(String defaultSystemPrompt) { this.defaultSystemPrompt = defaultSystemPrompt; }

    public Object getDefaultOptions() { return defaultOptions; }
    public void setDefaultOptions(Object defaultOptions) { this.defaultOptions = defaultOptions; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
