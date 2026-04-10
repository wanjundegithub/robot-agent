package robot.agent.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

public class CreateWorkflowVersionRequest {
    @JsonProperty("workflow_code")
    private String workflowCode;
    private String version;
    private String definition;
    @JsonProperty("entry_rule")
    private String entryRule;
    private String config;

    public String getWorkflowCode() { return workflowCode; }
    public void setWorkflowCode(String workflowCode) { this.workflowCode = workflowCode; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getDefinition() { return definition; }
    public void setDefinition(String definition) { this.definition = definition; }

    public String getEntryRule() { return entryRule; }
    public void setEntryRule(String entryRule) { this.entryRule = entryRule; }

    public String getConfig() { return config; }
    public void setConfig(String config) { this.config = config; }
}
