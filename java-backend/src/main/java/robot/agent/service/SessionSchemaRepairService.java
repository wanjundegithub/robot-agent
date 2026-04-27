package robot.agent.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Locale;

@Service
public class SessionSchemaRepairService {

    private final JdbcTemplate jdbcTemplate;

    public SessionSchemaRepairService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void ensureDeletedStatusSupported() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("SHOW COLUMNS FROM session LIKE 'status'");
        if (columns.isEmpty()) {
            return;
        }

        Object typeValue = columns.getFirst().get("Type");
        String type = typeValue == null ? "" : String.valueOf(typeValue).toLowerCase(Locale.ROOT);
        if (!type.startsWith("enum(") || type.contains("'deleted'")) {
            return;
        }

        jdbcTemplate.execute("ALTER TABLE session MODIFY COLUMN status ENUM('ACTIVE','CLOSED','EXPIRED','DELETED') NOT NULL");
    }
}
