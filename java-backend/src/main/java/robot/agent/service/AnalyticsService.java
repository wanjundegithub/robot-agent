package robot.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import robot.agent.model.Execution;
import robot.agent.model.ExecutionNodeLog;
import robot.agent.model.ExecutionStatus;
import robot.agent.repository.ExecutionNodeLogRepository;
import robot.agent.repository.ExecutionRepository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class AnalyticsService {

    private static final Map<String, Double> WORKFLOW_ALERTS = Map.of(
            "flight_booking", 80.0d,
            "hotel_booking", 60.0d,
            "general_query", 20.0d
    );
    private static final double USER_ALERT_THRESHOLD = 8.0d;
    private static final double GLOBAL_ALERT_THRESHOLD = 800.0d;

    private final ExecutionRepository executionRepository;
    private final ExecutionNodeLogRepository executionNodeLogRepository;
    private final PythonClient pythonClient;
    private final ObjectMapper objectMapper;

    public AnalyticsService(
            ExecutionRepository executionRepository,
            ExecutionNodeLogRepository executionNodeLogRepository,
            PythonClient pythonClient,
            ObjectMapper objectMapper
    ) {
        this.executionRepository = executionRepository;
        this.executionNodeLogRepository = executionNodeLogRepository;
        this.pythonClient = pythonClient;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> buildDashboard(String sessionId) {
        List<Execution> executions = loadExecutions(sessionId);
        Map<String, Object> response = new LinkedHashMap<>();

        int totalExecutions = executions.size();
        long activeExecutions = executions.stream().filter(execution -> !execution.getStatus().isTerminal()).count();
        long completedExecutions = executions.stream().filter(execution -> execution.getStatus() == ExecutionStatus.COMPLETED).count();
        long terminalExecutions = executions.stream().filter(execution -> execution.getStatus().isTerminal()).count();

        double successRate = terminalExecutions == 0 ? 0.0d : round((double) completedExecutions / terminalExecutions);
        double taskCompletionRate = totalExecutions == 0 ? 0.0d : round((double) completedExecutions / totalExecutions);
        double averageCompletionSeconds = round(executions.stream()
                .filter(execution -> execution.getStartedAt() != null && execution.getCompletedAt() != null)
                .mapToLong(execution -> Duration.between(execution.getStartedAt(), execution.getCompletedAt()).toSeconds())
                .average()
                .orElse(0.0d));
        double intentAccuracy = round(executions.stream()
                .map(this::routeConfidence)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0d));
        double humanInterventionRate = totalExecutions == 0 ? 0.0d : round((double) executionsWithHumanIntervention(executions).size() / totalExecutions);

        double totalCost = roundCost(executions.stream().mapToDouble(this::totalCost).sum());
        int totalInputTokens = executions.stream().mapToInt(this::inputTokens).sum();
        int totalOutputTokens = executions.stream().mapToInt(this::outputTokens).sum();

        response.put("summary", Map.of(
                "total_executions", totalExecutions,
                "active_executions", activeExecutions,
                "success_rate", successRate,
                "task_completion_rate", taskCompletionRate,
                "avg_completion_seconds", averageCompletionSeconds,
                "intent_accuracy", intentAccuracy,
                "human_intervention_rate", humanInterventionRate,
                "total_cost", totalCost,
                "input_tokens", totalInputTokens,
                "output_tokens", totalOutputTokens
        ));
        response.put("workflow_breakdown", workflowBreakdown(executions));
        response.put("experiment_summary", experimentSummary(executions));
        response.put("cost_alerts", buildCostAlerts(executions));
        return response;
    }

    public List<Map<String, Object>> buildCostAlerts(String sessionId) {
        return buildCostAlerts(loadExecutions(sessionId));
    }

    public Map<String, Object> buildReplay(String executionId) {
        Execution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new RuntimeException("Execution not found: " + executionId));
        List<ExecutionNodeLog> nodeLogs = executionNodeLogRepository.findByExecutionIdOrderByCreatedAtAsc(executionId);

        Map<String, Object> replay = new LinkedHashMap<>();
        replay.put("execution_id", execution.getId());
        replay.put("workflow_code", execution.getWorkflowCode());
        replay.put("workflow_version", execution.getWorkflowVersion());
        replay.put("session_id", execution.getSessionId());
        replay.put("status", execution.getStatus().getValue());
        replay.put("input_variables", parseJson(execution.getInputVariables()));
        replay.put("output_variables", parseJson(execution.getOutputVariables()));
        replay.put("variables", parseJson(execution.getVariables()));
        replay.put("metrics", parseJson(execution.getMetrics()));
        replay.put("node_logs", nodeLogs.stream().map(this::toReplayNodeLog).toList());
        replay.put("event_stream", buildReplayEvents(execution, nodeLogs));
        return replay;
    }

    public Map<String, Object> recommendSubflows(String workflowCode, String message) {
        return pythonClient.recommendSubflows(workflowCode, message).blockOptional().orElseGet(HashMap::new);
    }

    public Map<String, Object> evaluateRag(List<Map<String, Object>> dataset) {
        return pythonClient.evaluateRag(dataset).blockOptional().orElseGet(HashMap::new);
    }

    private List<Execution> loadExecutions(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return executionRepository.findAll();
        }
        return executionRepository.findBySessionIdOrderByCreatedAtDesc(sessionId);
    }

    private List<Map<String, Object>> workflowBreakdown(List<Execution> executions) {
        return executions.stream()
                .collect(Collectors.groupingBy(Execution::getWorkflowCode, LinkedHashMap::new, Collectors.toList()))
                .entrySet()
                .stream()
                .map(entry -> {
                    List<Execution> workflowExecutions = entry.getValue();
                    long completed = workflowExecutions.stream().filter(execution -> execution.getStatus() == ExecutionStatus.COMPLETED).count();
                    double completionRate = workflowExecutions.isEmpty() ? 0.0d : round((double) completed / workflowExecutions.size());
                    double averageCompletionSeconds = round(workflowExecutions.stream()
                            .filter(execution -> execution.getStartedAt() != null && execution.getCompletedAt() != null)
                            .mapToLong(execution -> Duration.between(execution.getStartedAt(), execution.getCompletedAt()).toSeconds())
                            .average()
                            .orElse(0.0d));
                    double cost = roundCost(workflowExecutions.stream().mapToDouble(this::totalCost).sum());
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("workflow_code", entry.getKey());
                    item.put("executions", workflowExecutions.size());
                    item.put("completion_rate", completionRate);
                    item.put("avg_completion_seconds", averageCompletionSeconds);
                    item.put("human_intervention_rate", round((double) executionsWithHumanIntervention(workflowExecutions).size() / Math.max(1, workflowExecutions.size())));
                    item.put("total_cost", cost);
                    return item;
                })
                .sorted(Comparator.comparing(item -> String.valueOf(item.get("workflow_code"))))
                .toList();
    }

    private List<Map<String, Object>> experimentSummary(List<Execution> executions) {
        return executions.stream()
                .map(execution -> parseJson(execution.getInputVariables()))
                .filter(input -> input.get("experiment_id") != null && input.get("experiment_group") != null)
                .collect(Collectors.groupingBy(
                        input -> input.get("experiment_id") + ":" + input.get("experiment_group"),
                        LinkedHashMap::new,
                        Collectors.counting()
                ))
                .entrySet()
                .stream()
                .map(entry -> {
                    String[] parts = entry.getKey().split(":", 2);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("experiment_id", parts[0]);
                    item.put("experiment_group", parts.length > 1 ? parts[1] : "unknown");
                    item.put("executions", entry.getValue());
                    return item;
                })
                .toList();
    }

    private List<Map<String, Object>> buildCostAlerts(List<Execution> executions) {
        List<Map<String, Object>> alerts = new ArrayList<>();
        double globalCost = executions.stream().mapToDouble(this::totalCost).sum();
        if (globalCost >= GLOBAL_ALERT_THRESHOLD) {
            alerts.add(alert("global", "all", globalCost, GLOBAL_ALERT_THRESHOLD));
        }

        executions.stream()
                .collect(Collectors.groupingBy(Execution::getWorkflowCode, LinkedHashMap::new, Collectors.toList()))
                .forEach((workflowCode, workflowExecutions) -> {
                    double totalCost = workflowExecutions.stream().mapToDouble(this::totalCost).sum();
                    Double threshold = WORKFLOW_ALERTS.get(workflowCode);
                    if (threshold != null && totalCost >= threshold) {
                        alerts.add(alert("workflow", workflowCode, totalCost, threshold));
                    }
                });

        executions.stream()
                .collect(Collectors.groupingBy(this::userId, LinkedHashMap::new, Collectors.toList()))
                .forEach((userId, userExecutions) -> {
                    double totalCost = userExecutions.stream().mapToDouble(this::totalCost).sum();
                    if (totalCost >= USER_ALERT_THRESHOLD) {
                        alerts.add(alert("user", userId, totalCost, USER_ALERT_THRESHOLD));
                    }
                });

        return alerts;
    }

    private Map<String, Object> alert(String scope, String scopeId, double totalCost, double threshold) {
        return Map.of(
                "scope", scope,
                "scope_id", scopeId,
                "total_cost", roundCost(totalCost),
                "threshold", threshold,
                "message", scope + " cost reached alert threshold"
        );
    }

    private List<String> executionsWithHumanIntervention(List<Execution> executions) {
        return executions.stream()
                .filter(execution -> {
                    if (execution.getStatus() == ExecutionStatus.WAITING_USER || execution.getStatus() == ExecutionStatus.SUSPENDED) {
                        return true;
                    }
                    return executionNodeLogRepository.findByExecutionIdOrderByCreatedAtAsc(execution.getId()).stream()
                            .anyMatch(log -> "form".equalsIgnoreCase(log.getNodeType()));
                })
                .map(Execution::getId)
                .toList();
    }

    private Map<String, Object> toReplayNodeLog(ExecutionNodeLog log) {
        Map<String, Object> replayNode = new LinkedHashMap<>();
        replayNode.put("node_id", log.getNodeId());
        replayNode.put("node_type", log.getNodeType());
        replayNode.put("status", log.getStatus());
        replayNode.put("started_at", log.getStartedAt());
        replayNode.put("completed_at", log.getCompletedAt());
        replayNode.put("input", parseJson(log.getInput()));
        replayNode.put("output", parseJson(log.getOutput()));
        replayNode.put("metrics", parseJson(log.getMetrics()));
        replayNode.put("error", log.getError());
        return replayNode;
    }

    private List<Map<String, Object>> buildReplayEvents(Execution execution, List<ExecutionNodeLog> nodeLogs) {
        List<Map<String, Object>> events = new ArrayList<>();
        events.add(Map.of(
                "event_type", "execution.started",
                "execution_id", execution.getId(),
                "workflow_code", execution.getWorkflowCode(),
                "workflow_version", execution.getWorkflowVersion()
        ));
        for (ExecutionNodeLog log : nodeLogs) {
            events.add(Map.of(
                    "event_type", "node." + log.getStatus(),
                    "execution_id", execution.getId(),
                    "node_id", log.getNodeId(),
                    "node_type", log.getNodeType()
            ));
        }
        events.add(Map.of(
                "event_type", "execution." + execution.getStatus().getValue(),
                "execution_id", execution.getId(),
                "final_output", parseJson(execution.getOutputVariables())
        ));
        return events;
    }

    private Double routeConfidence(Execution execution) {
        Object value = parseJson(execution.getInputVariables()).get("route_confidence");
        if (value == null) {
            value = parseJson(execution.getMetrics()).get("route_confidence");
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String userId(Execution execution) {
        Object value = parseJson(execution.getInputVariables()).get("user_id");
        return value == null ? "anonymous" : String.valueOf(value);
    }

    private double totalCost(Execution execution) {
        Object value = parseJson(execution.getMetrics()).get("total_cost");
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? 0.0d : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0.0d;
        }
    }

    private int inputTokens(Execution execution) {
        return intValue(parseJson(execution.getMetrics()).get("input_tokens"));
    }

    private int outputTokens(Execution execution) {
        return intValue(parseJson(execution.getMetrics()).get("output_tokens"));
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private double round(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private double roundCost(double value) {
        return Math.round(value * 1_000_000.0d) / 1_000_000.0d;
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            JsonNode node = readLenientJson(json);
            return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            return new HashMap<>();
        }
    }

    private JsonNode readLenientJson(String raw) throws Exception {
        String candidate = raw;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                JsonNode node = objectMapper.readTree(candidate);
                if (node.isTextual()) {
                    candidate = node.asText();
                    continue;
                }
                return node;
            } catch (Exception ignored) {
                candidate = candidate.trim();
                if (candidate.startsWith("\"") && candidate.endsWith("\"") && candidate.length() >= 2) {
                    candidate = candidate.substring(1, candidate.length() - 1);
                }
                candidate = candidate.replace("\\\"", "\"").replace("\"\"", "\"");
            }
        }
        return objectMapper.readTree("{}");
    }
}
