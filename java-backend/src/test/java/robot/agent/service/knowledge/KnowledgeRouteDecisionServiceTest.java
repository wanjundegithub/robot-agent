package robot.agent.service.knowledge;

import org.junit.jupiter.api.Test;
import robot.agent.config.KnowledgeProperties;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeRouteDecisionServiceTest {

    private final KnowledgeRouteDecisionService service = new KnowledgeRouteDecisionService(new KnowledgeProperties());

    @Test
    void highIntentWins() {
        assertThat(service.decide(0.75, 0.99).finalRoute()).isEqualTo("INTENT");
    }

    @Test
    void strongKnowledgeWinsWhenIntentLow() {
        assertThat(service.decide(0.74, 0.65).finalRoute()).isEqualTo("KNOWLEDGE");
    }

    @Test
    void ambiguousBothSidesClarifies() {
        assertThat(service.decide(0.60, 0.60).finalRoute()).isEqualTo("CLARIFY");
    }

    @Test
    void bothLowFallsBack() {
        assertThat(service.decide(0.54, 0.54).finalRoute()).isEqualTo("FALLBACK");
    }
}
