package robot.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "robot.workflow.routing")
public class WorkflowRoutingProperties {

    private Double regexAcceptThreshold;
    private Double phraseAcceptThreshold;
    private Double ragAcceptThreshold;
    private Double singleRagAcceptThreshold;
    private Double llmAcceptThreshold;

    public double getRegexAcceptThreshold() {
        return requireConfigured("regex-accept-threshold", regexAcceptThreshold);
    }

    public void setRegexAcceptThreshold(double regexAcceptThreshold) {
        this.regexAcceptThreshold = clamp(regexAcceptThreshold);
    }

    public double getPhraseAcceptThreshold() {
        return requireConfigured("phrase-accept-threshold", phraseAcceptThreshold);
    }

    public void setPhraseAcceptThreshold(double phraseAcceptThreshold) {
        this.phraseAcceptThreshold = clamp(phraseAcceptThreshold);
    }

    public double getRagAcceptThreshold() {
        return requireConfigured("rag-accept-threshold", ragAcceptThreshold);
    }

    public void setRagAcceptThreshold(double ragAcceptThreshold) {
        this.ragAcceptThreshold = clamp(ragAcceptThreshold);
    }

    public double getSingleRagAcceptThreshold() {
        return requireConfigured("single-rag-accept-threshold", singleRagAcceptThreshold);
    }

    public void setSingleRagAcceptThreshold(double singleRagAcceptThreshold) {
        this.singleRagAcceptThreshold = clamp(singleRagAcceptThreshold);
    }

    public double getLlmAcceptThreshold() {
        return requireConfigured("llm-accept-threshold", llmAcceptThreshold);
    }

    public void setLlmAcceptThreshold(double llmAcceptThreshold) {
        this.llmAcceptThreshold = clamp(llmAcceptThreshold);
    }

    private double requireConfigured(String propertyName, Double value) {
        if (value == null) {
            throw new IllegalStateException("robot.workflow.routing." + propertyName + " must be configured");
        }
        return value;
    }

    private double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0.0d;
        }
        if (value < 0.0d) {
            return 0.0d;
        }
        if (value > 1.0d) {
            return 1.0d;
        }
        return value;
    }
}
