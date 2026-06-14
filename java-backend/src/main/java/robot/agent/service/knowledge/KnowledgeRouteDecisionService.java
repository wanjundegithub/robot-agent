package robot.agent.service.knowledge;

import org.springframework.stereotype.Service;
import robot.agent.config.KnowledgeProperties;

@Service
public class KnowledgeRouteDecisionService {
    private final KnowledgeProperties properties;

    public KnowledgeRouteDecisionService(KnowledgeProperties properties) {
        this.properties = properties;
    }

    public Decision decide(double intentConfidence, double knowledgeBestScore) {
        KnowledgeProperties.Route route = properties.getRoute();
        if (intentConfidence >= route.getIntentPrimaryThreshold()) {
            return new Decision("INTENT", "intent_primary");
        }
        if (intentConfidence < route.getIntentPrimaryThreshold() && knowledgeBestScore >= route.getKnowledgePrimaryThreshold()) {
            return new Decision("KNOWLEDGE", "knowledge_primary");
        }
        if (intentConfidence >= route.getIntentClarifyThreshold()
                && intentConfidence < route.getIntentPrimaryThreshold()
                && knowledgeBestScore >= route.getKnowledgeClarifyThreshold()
                && knowledgeBestScore < route.getKnowledgePrimaryThreshold()) {
            return new Decision("CLARIFY", "both_ambiguous");
        }
        if (intentConfidence < route.getIntentClarifyThreshold() && knowledgeBestScore < route.getKnowledgeClarifyThreshold()) {
            return new Decision("FALLBACK", "both_low");
        }
        if (intentConfidence >= route.getIntentClarifyThreshold()) {
            return new Decision("CLARIFY", "intent_ambiguous");
        }
        if (knowledgeBestScore >= route.getKnowledgeClarifyThreshold()) {
            return new Decision("CLARIFY", "knowledge_ambiguous");
        }
        return new Decision("FALLBACK", "boundary_fallback");
    }

    public double knowledgePrimaryThreshold() {
        return properties.getRoute().getKnowledgePrimaryThreshold();
    }

    public double intentPrimaryThreshold() {
        return properties.getRoute().getIntentPrimaryThreshold();
    }

    public double knowledgeClarifyThreshold() {
        return properties.getRoute().getKnowledgeClarifyThreshold();
    }

    public record Decision(String finalRoute, String routeReason) {
    }
}
