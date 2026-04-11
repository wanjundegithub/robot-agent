package robot.agent.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EntryProtectionServiceTest {

    @Test
    void evaluateExecutionStartLimitsBurstPerSession() {
        EntryProtectionService service = new EntryProtectionService();

        EntryProtectionService.ProtectionDecision first = service.evaluateExecutionStart("demo-user", "sess_1", "general_query", null);
        EntryProtectionService.ProtectionDecision second = service.evaluateExecutionStart("demo-user", "sess_1", "general_query", null);
        EntryProtectionService.ProtectionDecision third = service.evaluateExecutionStart("demo-user", "sess_1", "general_query", null);
        EntryProtectionService.ProtectionDecision fourth = service.evaluateExecutionStart("demo-user", "sess_1", "general_query", null);

        assertThat(first.allowed()).isTrue();
        assertThat(second.allowed()).isTrue();
        assertThat(third.allowed()).isTrue();
        assertThat(fourth.allowed()).isFalse();
        assertThat(fourth.status()).isEqualTo("rate_limited");
        assertThat(fourth.reason()).isEqualTo("session_window_exceeded");
    }

    @Test
    void evaluateExecutionStartDegradesWhenPythonCircuitIsOpen() {
        EntryProtectionService service = new EntryProtectionService();
        service.recordPythonFailure("timeout");
        service.recordPythonFailure("timeout");
        service.recordPythonFailure("timeout");

        EntryProtectionService.ProtectionDecision decision = service.evaluateExecutionStart(
                "demo-user",
                "sess_2",
                "general_query",
                null
        );

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.status()).isEqualTo("degraded");
        assertThat(decision.reason()).isEqualTo("python_circuit_open");
    }
}
