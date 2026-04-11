package robot.agent.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import robot.agent.model.Execution;
import robot.agent.model.ExecutionStatus;
import robot.agent.repository.ExecutionNodeLogRepository;
import robot.agent.repository.ExecutionRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArchiveLifecycleServiceTest {

    @Mock
    private ExecutionRepository executionRepository;

    @Mock
    private ExecutionNodeLogRepository executionNodeLogRepository;

    @Test
    void buildLifecycleViewSplitsHotWarmAndColdData() {
        when(executionRepository.findAll()).thenReturn(List.of(
                execution("exec_hot", 1),
                execution("exec_warm", 30),
                execution("exec_cold", 120)
        ));
        when(executionNodeLogRepository.findByExecutionIdOrderByCreatedAtAsc("exec_hot")).thenReturn(List.of());
        when(executionNodeLogRepository.findByExecutionIdOrderByCreatedAtAsc("exec_warm")).thenReturn(List.of());
        when(executionNodeLogRepository.findByExecutionIdOrderByCreatedAtAsc("exec_cold")).thenReturn(List.of());

        ArchiveLifecycleService service = new ArchiveLifecycleService(executionRepository, executionNodeLogRepository);
        Map<String, Object> lifecycle = service.buildLifecycleView(null);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) lifecycle.get("summary");

        assertThat(summary.get("hot_executions")).isEqualTo(1L);
        assertThat(summary.get("warm_executions")).isEqualTo(1L);
        assertThat(summary.get("cold_executions")).isEqualTo(1L);
        assertThat(summary.get("cleanup_candidates")).isEqualTo(2L);
    }

    private Execution execution(String executionId, int ageDays) {
        Execution execution = new Execution();
        execution.setId(executionId);
        execution.setSessionId("sess_archive");
        execution.setWorkflowCode("general_query");
        execution.setWorkflowVersion("1.0.0");
        execution.setStatus(ExecutionStatus.COMPLETED);
        execution.setCreatedAt(LocalDateTime.now().minusDays(ageDays));
        return execution;
    }
}
