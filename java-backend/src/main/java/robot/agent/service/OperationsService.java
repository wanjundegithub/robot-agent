package robot.agent.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class OperationsService {

    private final EntryProtectionService entryProtectionService;
    private final ArchiveLifecycleService archiveLifecycleService;

    public OperationsService(
            EntryProtectionService entryProtectionService,
            ArchiveLifecycleService archiveLifecycleService
    ) {
        this.entryProtectionService = entryProtectionService;
        this.archiveLifecycleService = archiveLifecycleService;
    }

    public Map<String, Object> buildReadiness(String sessionId) {
        Map<String, Object> readiness = new LinkedHashMap<>();
        readiness.put("protection", entryProtectionService.snapshot());
        readiness.put("archive", archiveLifecycleService.buildLifecycleView(sessionId));
        readiness.put("platform", Map.of(
                "index_targets", List.of(
                        Map.of("table", "execution", "goal", "session_status_created_at_lookup"),
                        Map.of("table", "execution_node_log", "goal", "execution_id_created_at_lookup"),
                        Map.of("table", "audit_log", "goal", "workspace_action_created_at_lookup")
                ),
                "redis_cluster", Map.of(
                        "key_patterns", List.of(
                                "session:{session_id}",
                                "rate_limit:{user_id}",
                                "tool_confirm:{execution_id}:{tool_code}"
                        ),
                        "consistency", "ttl_scoped",
                        "fallback", "local_memory_guard"
                ),
                "vector_sharding", Map.of(
                        "strategy", "hash(kb_code, normalized_query)%4",
                        "shard_count", 4,
                        "fallback", "primary_shard"
                )
        ));
        return readiness;
    }
}
