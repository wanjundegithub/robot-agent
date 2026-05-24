package robot.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "robot.model.default")
public class DefaultModelProperties {

    private String modelCode;
    private Map<String, String> purposeModelCodes = new LinkedHashMap<>();

    public String getModelCode() {
        return modelCode;
    }

    public void setModelCode(String modelCode) {
        this.modelCode = modelCode;
    }

    public Map<String, String> getPurposeModelCodes() {
        return purposeModelCodes;
    }

    public void setPurposeModelCodes(Map<String, String> purposeModelCodes) {
        this.purposeModelCodes = purposeModelCodes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(purposeModelCodes);
    }

    public String resolveModelCode(String purpose) {
        String purposeModelCode = blankToNull(purposeModelCodes.get(purpose));
        if (purposeModelCode != null) {
            return purposeModelCode;
        }
        return blankToNull(modelCode);
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
