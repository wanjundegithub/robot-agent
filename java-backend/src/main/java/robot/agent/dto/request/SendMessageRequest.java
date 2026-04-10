package robot.agent.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class SendMessageRequest {
    @JsonProperty("message_id")
    private String messageId;
    private String content;
    private List<Object> attachments;
    @JsonProperty("user_id")
    private String userId;
    @JsonProperty("confirm_switch")
    private Boolean confirmSwitch;

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public List<Object> getAttachments() { return attachments; }
    public void setAttachments(List<Object> attachments) { this.attachments = attachments; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public Boolean getConfirmSwitch() { return confirmSwitch; }
    public void setConfirmSwitch(Boolean confirmSwitch) { this.confirmSwitch = confirmSwitch; }
}
