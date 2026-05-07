package robot.agent.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

public class CreateWorkflowVersionRequest {
    @JsonProperty("workflow_code")
    private String workflowCode;
    @JsonProperty("workflow_name")
    private String workflowName;
    private String version;
    private String definition;
    @JsonProperty("entry_rule")
    private String entryRule;
    @JsonProperty("editor_meta")
    private String editorMeta;
    private String config;
    @JsonProperty("workflow_snapshot")
    private String workflowSnapshot;

    public String getWorkflowCode() { return workflowCode; }
    public void setWorkflowCode(String workflowCode) { this.workflowCode = workflowCode; }

    public String getWorkflowName() { return workflowName; }
    public void setWorkflowName(String workflowName) { this.workflowName = workflowName; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getDefinition() { return definition; }
    public void setDefinition(String definition) { this.definition = definition; }

    public String getEntryRule() { return entryRule; }
    public void setEntryRule(String entryRule) { this.entryRule = entryRule; }

    public String getEditorMeta() { return editorMeta; }
    public void setEditorMeta(String editorMeta) { this.editorMeta = editorMeta; }

    public String getConfig() { return config; }
    public void setConfig(String config) { this.config = config; }

    public String getWorkflowSnapshot() { return workflowSnapshot; }
    public void setWorkflowSnapshot(String workflowSnapshot) { this.workflowSnapshot = workflowSnapshot; }
}
