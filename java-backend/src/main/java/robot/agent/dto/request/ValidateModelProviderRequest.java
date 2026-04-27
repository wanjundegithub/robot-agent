package robot.agent.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public class ValidateModelProviderRequest {
    @JsonProperty("provider_type")
    private String providerType;
    @JsonProperty("base_url")
    private String baseUrl;
    @JsonProperty("api_key_secret_ref")
    private String apiKeySecretRef;
    @JsonProperty("model_code")
    private String modelCode;
    private String purpose;
    @JsonProperty("system_prompt")
    private String systemPrompt;
    private String message;
    @JsonProperty("request_body")
    private Map<String, Object> requestBody;

    public String getProviderType() { return providerType; }
    public void setProviderType(String providerType) { this.providerType = providerType; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKeySecretRef() { return apiKeySecretRef; }
    public void setApiKeySecretRef(String apiKeySecretRef) { this.apiKeySecretRef = apiKeySecretRef; }

    public String getModelCode() { return modelCode; }
    public void setModelCode(String modelCode) { this.modelCode = modelCode; }

    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Map<String, Object> getRequestBody() { return requestBody; }
    public void setRequestBody(Map<String, Object> requestBody) { this.requestBody = requestBody; }
}
