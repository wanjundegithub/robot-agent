package robot.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import robot.agent.model.Execution;
import robot.agent.model.ExecutionNodeLog;
import robot.agent.model.ExecutionStatus;
import robot.agent.repository.ExecutionNodeLogRepository;
import robot.agent.repository.ExecutionRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    @Mock
    private ExecutionRepository executionRepository;

    @Mock
    private ExecutionNodeLogRepository executionNodeLogRepository;

    private AnalyticsService analyticsService;

    @BeforeEach
    void setUp() {
        analyticsService = new AnalyticsService(
                executionRepository,
                executionNodeLogRepository,
                new StubPythonClient(),
                new ObjectMapper()
        );
    }

    @Test
    void buildDashboardAggregatesPhase4Metrics() {
        Execution execution = new Execution();
        execution.setId("exec_1");
        execution.setSessionId("sess_1");
        execution.setWorkflowCode("flight_booking");
        execution.setWorkflowVersion("2.0.0");
        execution.setStatus(ExecutionStatus.COMPLETED);
        execution.setStartedAt(LocalDateTime.now().minusMinutes(2));
        execution.setCompletedAt(LocalDateTime.now());
        execution.setInputVariables("{\"route_confidence\":0.93,\"experiment_id\":\"phase4-routing-ab\",\"experiment_group\":\"A\",\"user_id\":\"demo-user\"}");
        execution.setMetrics("{\"total_cost\":12.5,\"input_tokens\":1200,\"output_tokens\":600}");

        ExecutionNodeLog formLog = new ExecutionNodeLog();
        formLog.setExecutionId("exec_1");
        formLog.setNodeId("collect_info");
        formLog.setNodeType("form");
        formLog.setStatus("completed");

        when(executionRepository.findAll()).thenReturn(List.of(execution));
        when(executionNodeLogRepository.findByExecutionIdOrderByCreatedAtAsc("exec_1")).thenReturn(List.of(formLog));

        Map<String, Object> dashboard = analyticsService.buildDashboard(null);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) dashboard.get("summary");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> experimentSummary = (List<Map<String, Object>>) dashboard.get("experiment_summary");

        assertThat(summary.get("total_executions")).isEqualTo(1);
        assertThat(summary.get("total_cost")).isEqualTo(12.5d);
        assertThat(experimentSummary).hasSize(1);
    }

    @Test
    void buildReplayReconstructsNodeEvents() {
        Execution execution = new Execution();
        execution.setId("exec_1");
        execution.setSessionId("sess_1");
        execution.setWorkflowCode("general_query");
        execution.setWorkflowVersion("1.0.0");
        execution.setStatus(ExecutionStatus.COMPLETED);
        execution.setInputVariables("{\"user_message\":\"退票规则\"}");
        execution.setOutputVariables("{\"answer\":\"根据知识库检索结果...\"}");

        ExecutionNodeLog nodeLog = new ExecutionNodeLog();
        nodeLog.setExecutionId("exec_1");
        nodeLog.setNodeId("retrieve_policy");
        nodeLog.setNodeType("knowledge");
        nodeLog.setStatus("completed");

        when(executionRepository.findById("exec_1")).thenReturn(java.util.Optional.of(execution));
        when(executionNodeLogRepository.findByExecutionIdOrderByCreatedAtAsc("exec_1")).thenReturn(List.of(nodeLog));

        Map<String, Object> replay = analyticsService.buildReplay("exec_1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> eventStream = (List<Map<String, Object>>) replay.get("event_stream");

        assertThat(replay.get("execution_id")).isEqualTo("exec_1");
        assertThat(eventStream).isNotEmpty();
    }

    private static class StubPythonClient extends PythonClient {
        StubPythonClient() {
            super("http://localhost:8000");
        }

        @Override
        public Mono<Map<String, Object>> recommendSubflows(String workflowCode, String message) {
            return Mono.just(Map.of(
                    "workflow_code", workflowCode,
                    "recommendations", List.of(Map.of(
                            "subflow_code", "seat_check",
                            "subflow_version", "1.0.0",
                            "score", 0.85
                    ))
            ));
        }

        @Override
        public Mono<Map<String, Object>> evaluateRag(List<Map<String, Object>> dataset) {
            return Mono.just(Map.of(
                    "dataset_size", dataset == null ? 0 : dataset.size(),
                    "hit_rate", 1.0,
                    "avg_relevance", 1.0
            ));
        }
    }
}
