package robot.agent.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class WorkflowSchemaRepairService {

    private final JdbcTemplate jdbcTemplate;

    public WorkflowSchemaRepairService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void ensureArchivedWorkflowStatusSupported() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("SHOW COLUMNS FROM workflow_definition LIKE 'status'");
        if (columns.isEmpty()) {
            return;
        }

        Object typeValue = columns.getFirst().get("Type");
        String type = typeValue == null ? "" : String.valueOf(typeValue).toLowerCase(Locale.ROOT);
        if (!type.startsWith("enum(") || type.contains("'archived'")) {
            return;
        }

        jdbcTemplate.execute("ALTER TABLE workflow_definition MODIFY COLUMN status ENUM('DRAFT','PUBLISHED','ARCHIVED') NOT NULL");
    }

    public void ensureWorkflowSnapshotColumnSupported() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("SHOW COLUMNS FROM workflow_version LIKE 'workflow_snapshot'");
        if (!columns.isEmpty()) {
            return;
        }
        jdbcTemplate.execute("ALTER TABLE workflow_version ADD COLUMN workflow_snapshot JSON NULL");
    }
}
