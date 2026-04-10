package robot.agent.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public class FormSubmitRequest {
    @JsonProperty("submit_id")
    private String submitId;
    @JsonProperty("form_data")
    private Map<String, Object> formData;

    public String getSubmitId() { return submitId; }
    public void setSubmitId(String submitId) { this.submitId = submitId; }

    public Map<String, Object> getFormData() { return formData; }
    public void setFormData(Map<String, Object> formData) { this.formData = formData; }
}
