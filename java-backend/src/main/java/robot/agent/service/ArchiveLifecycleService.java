package robot.agent.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import robot.agent.model.Execution;
import robot.agent.repository.ExecutionNodeLogRepository;
import robot.agent.repository.ExecutionRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class ArchiveLifecycleService {

    private final ExecutionRepository executionRepository;
    private final ExecutionNodeLogRepository executionNodeLogRepository;

    public ArchiveLifecycleService(
            ExecutionRepository executionRepository,
            ExecutionNodeLogRepository executionNodeLogRepository
    ) {
        this.executionRepository = executionRepository;
        this.executionNodeLogRepository = executionNodeLogRepository;
    }

    public Map<String, Object> buildLifecycleView(String sessionId) {
        List<Execution> executions = (sessionId == null || sessionId.isBlank())
                ? executionRepository.findAll()
                : executionRepository.findBySessionIdOrderByCreatedAtDesc(sessionId);

        long hotCount = executions.stream().filter(execution -> "hot".equals(tierFor(execution))).count();
        long warmCount = executions.stream().filter(execution -> "warm".equals(tierFor(execution))).count();
        long coldCount = executions.stream().filter(execution -> "cold".equals(tierFor(execution))).count();

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("retention", Map.of(
                "hot_days", 7,
                "warm_days", 90,
                "cold_storage", "minio_or_s3",
                "key_node_types", List.of("start", "end", "form", "tool", "llm", "failed")
        ));
        view.put("summary", Map.of(
                "total_executions", executions.size(),
                "hot_executions", hotCount,
                "warm_executions", warmCount,
                "cold_executions", coldCount,
                "cleanup_candidates", warmCount + coldCount
        ));
        view.put("recent_candidates", executions.stream()
                .sorted(Comparator.comparing(Execution::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(8)
                .map(execution -> Map.of(
                        "execution_id", execution.getId(),
                        "workflow_code", execution.getWorkflowCode(),
                        "status", execution.getStatus().getValue(),
                        "tier", tierFor(execution),
                        "archive_target", archiveTarget(execution),
                        "cleanup_action", cleanupAction(execution),
                        "node_log_count", executionNodeLogRepository.findByExecutionIdOrderByCreatedAtAsc(execution.getId()).size(),
                        "created_at", String.valueOf(execution.getCreatedAt())
                ))
                .toList());
        view.put("restore_entrypoints", List.of(
                "/api/executions/{executionId}/replay",
                "archive_preview_only_for_cold_data"
        ));
        return view;
    }

    private String tierFor(Execution execution) {
        LocalDateTime createdAt = execution.getCreatedAt() == null ? LocalDateTime.now() : execution.getCreatedAt();
        long days = Math.max(0L, Duration.between(createdAt, LocalDateTime.now()).toDays());
        if (days <= 7) {
            return "hot";
        }
        if (days <= 90) {
            return "warm";
        }
        return "cold";
    }

    private String archiveTarget(Execution execution) {
        return switch (tierFor(execution)) {
            case "hot" -> "mysql_primary";
            case "warm" -> "mysql_archive";
            default -> "minio_or_s3";
        };
    }

    private String cleanupAction(Execution execution) {
        return switch (tierFor(execution)) {
            case "hot" -> "retain_full";
            case "warm" -> "archive_key_nodes";
            default -> "compress_and_offload";
        };
    }
}
