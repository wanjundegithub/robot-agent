package robot.agent.service;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;

@Service
public class EntryProtectionService {

    private static final String PYTHON_EXECUTE_CIRCUIT = "python.execute";

    private final ConcurrentMap<String, Deque<Instant>> windows = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CircuitState> circuits = new ConcurrentHashMap<>();

    public ProtectionDecision evaluateExecutionStart(
            String userId,
            String sessionId,
            String workflowCode,
            String requestedToolCode
    ) {
        CircuitState circuitState = circuits.computeIfAbsent(PYTHON_EXECUTE_CIRCUIT, ignored -> new CircuitState());
        if (circuitState.isOpen()) {
            return ProtectionDecision.degradedDecision(
                    "python_circuit_open",
                    "Python runtime temporarily degraded, please retry later."
            );
        }

        Instant now = Instant.now();
        WindowEvaluation userEvaluation = evaluateWindow("user:" + safe(userId), 6, Duration.ofSeconds(60), now);
        if (!userEvaluation.allowed()) {
            return ProtectionDecision.rateLimitedDecision("user_window_exceeded", userEvaluation.retryAfterSeconds());
        }

        WindowEvaluation sessionEvaluation = evaluateWindow("session:" + safe(sessionId), 3, Duration.ofSeconds(30), now);
        if (!sessionEvaluation.allowed()) {
            return ProtectionDecision.rateLimitedDecision("session_window_exceeded", sessionEvaluation.retryAfterSeconds());
        }

        if (requestedToolCode != null && !requestedToolCode.isBlank()) {
            WindowEvaluation toolEvaluation = evaluateWindow(
                    "tool:" + requestedToolCode,
                    2,
                    Duration.ofMinutes(5),
                    now
            );
            if (!toolEvaluation.allowed()) {
                return ProtectionDecision.rateLimitedDecision("high_risk_window_exceeded", toolEvaluation.retryAfterSeconds());
            }
        }

        if (workflowCode != null && !workflowCode.isBlank()) {
            int workflowLimit = "general_query".equals(workflowCode) ? 10 : 4;
            WindowEvaluation workflowEvaluation = evaluateWindow(
                    "workflow:" + workflowCode,
                    workflowLimit,
                    Duration.ofSeconds(60),
                    now
            );
            if (!workflowEvaluation.allowed()) {
                return ProtectionDecision.rateLimitedDecision("workflow_window_exceeded", workflowEvaluation.retryAfterSeconds());
            }
        }

        return ProtectionDecision.allowedDecision();
    }

    public void recordPythonFailure(String reason) {
        CircuitState circuitState = circuits.computeIfAbsent(PYTHON_EXECUTE_CIRCUIT, ignored -> new CircuitState());
        circuitState.recordFailure(reason);
    }

    public void recordPythonSuccess() {
        circuits.computeIfAbsent(PYTHON_EXECUTE_CIRCUIT, ignored -> new CircuitState()).recordSuccess();
    }

    public Map<String, Object> snapshot() {
        CircuitState circuitState = circuits.computeIfAbsent(PYTHON_EXECUTE_CIRCUIT, ignored -> new CircuitState());
        Map<String, Object> protection = new LinkedHashMap<>();
        Map<String, Object> circuit = new LinkedHashMap<>();
        circuit.put("name", PYTHON_EXECUTE_CIRCUIT);
        circuit.put("state", circuitState.state());
        circuit.put("failure_threshold", 3);
        circuit.put("open_seconds", 30);
        circuit.put("last_error", circuitState.lastError());
        protection.put("circuit", circuit);
        protection.put("rate_limits", List.of(
                Map.of("scope", "user", "limit", 6, "window_seconds", 60, "redis_key_pattern", "rate_limit:{user_id}"),
                Map.of("scope", "session", "limit", 3, "window_seconds", 30, "redis_key_pattern", "session_limit:{session_id}"),
                Map.of("scope", "workflow", "limit", 4, "window_seconds", 60, "redis_key_pattern", "workflow_limit:{workflow_code}"),
                Map.of("scope", "tool", "limit", 2, "window_seconds", 300, "redis_key_pattern", "tool_confirm:{execution_id}:{tool_code}")
        ));
        protection.put("degradation_modes", List.of(
                "entry_rejection",
                "python_circuit_guard",
                "frontend_status_feedback"
        ));
        return protection;
    }

    private WindowEvaluation evaluateWindow(String key, int limit, Duration window, Instant now) {
        Deque<Instant> values = windows.computeIfAbsent(key, ignored -> new ConcurrentLinkedDeque<>());
        synchronized (values) {
            Instant threshold = now.minus(window);
            while (!values.isEmpty() && values.peekFirst().isBefore(threshold)) {
                values.pollFirst();
            }
            if (values.size() >= limit) {
                Instant earliest = values.peekFirst();
                long retryAfter = earliest == null
                        ? window.getSeconds()
                        : Math.max(1L, window.minus(Duration.between(earliest, now)).getSeconds());
                return new WindowEvaluation(false, retryAfter);
            }
            values.addLast(now);
            return new WindowEvaluation(true, 0L);
        }
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "anonymous" : value;
    }

    private static final class CircuitState {
        private final List<String> failures = new ArrayList<>();
        private Instant openedUntil;

        synchronized boolean isOpen() {
            return openedUntil != null && openedUntil.isAfter(Instant.now());
        }

        synchronized void recordFailure(String reason) {
            if (openedUntil != null && openedUntil.isBefore(Instant.now())) {
                openedUntil = null;
                failures.clear();
            }
            failures.add(reason == null ? "unknown" : reason);
            if (failures.size() >= 3) {
                openedUntil = Instant.now().plusSeconds(30);
                failures.clear();
            }
        }

        synchronized void recordSuccess() {
            openedUntil = null;
            failures.clear();
        }

        synchronized String state() {
            return isOpen() ? "open" : "closed";
        }

        synchronized String lastError() {
            if (failures.isEmpty()) {
                return null;
            }
            return failures.get(failures.size() - 1);
        }
    }

    private record WindowEvaluation(boolean allowed, long retryAfterSeconds) {
    }

    public record ProtectionDecision(
            boolean allowed,
            String status,
            String reason,
            Long retryAfterSeconds,
            String degradationMessage
    ) {
        static ProtectionDecision allowedDecision() {
            return new ProtectionDecision(true, "allowed", null, null, null);
        }

        static ProtectionDecision rateLimitedDecision(String reason, long retryAfterSeconds) {
            return new ProtectionDecision(false, "rate_limited", reason, retryAfterSeconds, null);
        }

        static ProtectionDecision degradedDecision(String reason, String degradationMessage) {
            return new ProtectionDecision(false, "degraded", reason, null, degradationMessage);
        }
    }
}
