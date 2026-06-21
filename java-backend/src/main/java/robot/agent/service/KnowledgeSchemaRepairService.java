package robot.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class KnowledgeSchemaRepairService implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeSchemaRepairService.class);

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    public KnowledgeSchemaRepairService(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, null);
    }

    @Autowired
    public KnowledgeSchemaRepairService(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (dataSource == null) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            repairKnowledgeStatusColumns(metaData.getDatabaseProductName());
        }
    }

    void repairKnowledgeStatusColumns(String databaseProductName) {
        if (!isMysqlFamily(databaseProductName)) {
            return;
        }

        ensureVarcharStatusColumn("knowledge_base", "ACTIVE");
        ensureVarcharStatusColumn("knowledge_document", "PENDING");
        dropColumnIfExists("knowledge_base", "embedding_model");
    }

    private void ensureVarcharStatusColumn(String tableName, String defaultValue) {
        List<Map<String, Object>> columns;
        try {
            columns = jdbcTemplate.queryForList("SHOW COLUMNS FROM `" + tableName + "` LIKE 'status'");
        } catch (DataAccessException exception) {
            logger.debug("Skipped {}.status schema repair because the table is not available", tableName, exception);
            return;
        }
        if (columns.isEmpty()) {
            return;
        }

        Object rawType = columns.get(0).get("Type");
        String type = rawType == null ? "" : rawType.toString().toLowerCase(Locale.ROOT);
        if ("varchar(20)".equals(type)) {
            return;
        }

        String sql = "ALTER TABLE `" + tableName + "` MODIFY COLUMN `status` VARCHAR(20) NOT NULL DEFAULT '" + defaultValue + "'";
        jdbcTemplate.execute(sql);
        logger.info("Repaired {}.status column to VARCHAR(20)", tableName);
    }

    private void dropColumnIfExists(String tableName, String columnName) {
        List<Map<String, Object>> columns;
        try {
            columns = jdbcTemplate.queryForList("SHOW COLUMNS FROM `" + tableName + "` LIKE '" + columnName + "'");
        } catch (DataAccessException exception) {
            logger.debug("Skipped {}.{} schema repair because the table is not available", tableName, columnName, exception);
            return;
        }
        if (columns.isEmpty()) {
            return;
        }

        jdbcTemplate.execute("ALTER TABLE `" + tableName + "` DROP COLUMN `" + columnName + "`");
        logger.info("Dropped obsolete {}.{} column", tableName, columnName);
    }

    private boolean isMysqlFamily(String databaseProductName) {
        if (databaseProductName == null) {
            return false;
        }
        String normalized = databaseProductName.toLowerCase(Locale.ROOT);
        return normalized.contains("mysql") || normalized.contains("mariadb");
    }
}
