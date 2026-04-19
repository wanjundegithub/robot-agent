package robot.agent.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

public class TestModelProfileRequest {
    @JsonProperty("system_prompt")
    private String systemPrompt;
    private String message;

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
