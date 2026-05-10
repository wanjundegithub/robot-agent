package robot.agent.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.List;

public class SendMessageRequest {
    @JsonProperty("session_id")
    private String sessionId;
    @JsonProperty("message_id")
    private String messageId;
    @JsonAlias("message")
    private String content;
    private List<Object> attachments;
    @JsonProperty("user_id")
    private String userId;
    @JsonProperty("confirm_switch")
    private Boolean confirmSwitch;
    @JsonProperty("requested_tool_code")
    private String requestedToolCode;
    @JsonProperty("confirmation_id")
    private String confirmationId;
    @JsonProperty("cancel_confirmation")
    private Boolean cancelConfirmation;
    @JsonProperty("intent_candidate_action")
    private String intentCandidateAction;
    @JsonProperty("intent_candidate_target_code")
    private String intentCandidateTargetCode;
    @JsonProperty("workflow_code")
    private String workflowCode;
    @JsonProperty("workflow_id")
    private Long workflowId;
    @JsonProperty("workflow_version")
    private String workflowVersion;
    @JsonProperty("workflow_definition")
    private Map<String, Object> workflowDefinition;
    @JsonProperty("entry_rule")
    private Map<String, Object> entryRule;
    @JsonProperty("workflow_config")
    private Map<String, Object> workflowConfig;

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

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

    public String getRequestedToolCode() { return requestedToolCode; }
    public void setRequestedToolCode(String requestedToolCode) { this.requestedToolCode = requestedToolCode; }

    public String getConfirmationId() { return confirmationId; }
    public void setConfirmationId(String confirmationId) { this.confirmationId = confirmationId; }

    public Boolean getCancelConfirmation() { return cancelConfirmation; }
    public void setCancelConfirmation(Boolean cancelConfirmation) { this.cancelConfirmation = cancelConfirmation; }

    public String getIntentCandidateAction() { return intentCandidateAction; }
    public void setIntentCandidateAction(String intentCandidateAction) { this.intentCandidateAction = intentCandidateAction; }

    public String getIntentCandidateTargetCode() { return intentCandidateTargetCode; }
    public void setIntentCandidateTargetCode(String intentCandidateTargetCode) { this.intentCandidateTargetCode = intentCandidateTargetCode; }

    public String getWorkflowCode() { return workflowCode; }
    public void setWorkflowCode(String workflowCode) { this.workflowCode = workflowCode; }

    public Long getWorkflowId() { return workflowId; }
    public void setWorkflowId(Long workflowId) { this.workflowId = workflowId; }

    public String getWorkflowVersion() { return workflowVersion; }
    public void setWorkflowVersion(String workflowVersion) { this.workflowVersion = workflowVersion; }

    public Map<String, Object> getWorkflowDefinition() { return workflowDefinition; }
    public void setWorkflowDefinition(Map<String, Object> workflowDefinition) { this.workflowDefinition = workflowDefinition; }

    public Map<String, Object> getEntryRule() { return entryRule; }
    public void setEntryRule(Map<String, Object> entryRule) { this.entryRule = entryRule; }

    public Map<String, Object> getWorkflowConfig() { return workflowConfig; }
    public void setWorkflowConfig(Map<String, Object> workflowConfig) { this.workflowConfig = workflowConfig; }
}
