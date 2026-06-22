package robot.agent.service;

import jakarta.persistence.Column;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import robot.agent.model.KnowledgeBase;
import robot.agent.model.KnowledgeDocument;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.anyString;

class KnowledgeSchemaRepairServiceTest {

    @Test
    void knowledgeStatusColumnsUsePortableVarcharDefinitions() throws Exception {
        assertStatusColumnDefinition(KnowledgeBase.class);
        assertStatusColumnDefinition(KnowledgeDocument.class);
    }

    @Test
    void knowledgeBaseEntityDoesNotDeclareEmbeddingModelColumn() {
        assertThat(List.of(KnowledgeBase.class.getDeclaredFields()))
                .extracting(Field::getName)
                .doesNotContain("embeddingModel");
    }

    @Test
    void dropsObsoleteKnowledgeBaseEmbeddingModelColumn() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        KnowledgeSchemaRepairService service = new KnowledgeSchemaRepairService(jdbcTemplate);
        when(jdbcTemplate.queryForList("SHOW COLUMNS FROM `knowledge_base` LIKE 'status'"))
                .thenReturn(List.of(Map.of("Type", "varchar(20)")));
        when(jdbcTemplate.queryForList("SHOW COLUMNS FROM `knowledge_document` LIKE 'status'"))
                .thenReturn(List.of(Map.of("Type", "varchar(20)")));
        when(jdbcTemplate.queryForList("SHOW COLUMNS FROM `knowledge_base` LIKE 'embedding_model'"))
                .thenReturn(List.of(Map.of("Field", "embedding_model")));

        service.repairKnowledgeStatusColumns("MySQL");

        verify(jdbcTemplate).execute("ALTER TABLE `knowledge_base` DROP COLUMN `embedding_model`");
    }

    @Test
    void convertsLegacyMysqlKnowledgeStatusEnumsToVarcharColumns() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        KnowledgeSchemaRepairService service = new KnowledgeSchemaRepairService(jdbcTemplate);
        when(jdbcTemplate.queryForList("SHOW COLUMNS FROM `knowledge_base` LIKE 'status'"))
                .thenReturn(List.of(Map.of("Type", "enum('ACTIVE','INACTIVE')")));
        when(jdbcTemplate.queryForList("SHOW COLUMNS FROM `knowledge_document` LIKE 'status'"))
                .thenReturn(List.of(Map.of("Type", "enum('PENDING','PROCESSING','PROCESSED','FAILED')")));

        service.repairKnowledgeStatusColumns("MySQL");

        verify(jdbcTemplate).execute("ALTER TABLE `knowledge_base` MODIFY COLUMN `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'");
        verify(jdbcTemplate).execute("ALTER TABLE `knowledge_document` MODIFY COLUMN `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING'");
    }

    @Test
    void applicationRunnerUsesDatabaseProductNameForRepair() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("MySQL");
        when(jdbcTemplate.queryForList("SHOW COLUMNS FROM `knowledge_base` LIKE 'status'"))
                .thenReturn(List.of(Map.of("Type", "enum('ACTIVE','INACTIVE')")));
        when(jdbcTemplate.queryForList("SHOW COLUMNS FROM `knowledge_document` LIKE 'status'"))
                .thenReturn(List.of(Map.of("Type", "varchar(20)")));
        KnowledgeSchemaRepairService service = new KnowledgeSchemaRepairService(jdbcTemplate, dataSource);

        service.run(null);

        verify(jdbcTemplate).execute("ALTER TABLE `knowledge_base` MODIFY COLUMN `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'");
    }

    @Test
    void skipsRepairForNonMysqlDatabases() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        KnowledgeSchemaRepairService service = new KnowledgeSchemaRepairService(jdbcTemplate);

        service.repairKnowledgeStatusColumns("H2");

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void repairsWorkflowSpaceSchemaWhenTablesExist() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        KnowledgeSchemaRepairService service = new KnowledgeSchemaRepairService(jdbcTemplate);
        when(jdbcTemplate.queryForList("SHOW COLUMNS FROM `knowledge_base` LIKE 'status'"))
                .thenReturn(List.of(Map.of("Type", "varchar(20)")));
        when(jdbcTemplate.queryForList("SHOW COLUMNS FROM `knowledge_document` LIKE 'status'"))
                .thenReturn(List.of(Map.of("Type", "varchar(20)")));
        when(jdbcTemplate.queryForList("SHOW TABLES LIKE 'workflow_definition'"))
                .thenReturn(List.of(Map.of("Table", "workflow_definition")));
        when(jdbcTemplate.queryForList("SHOW COLUMNS FROM `workflow_definition` LIKE 'workflow_space_code'"))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForList("SHOW TABLES LIKE 'workflow_space'"))
                .thenReturn(List.of(Map.of("Table", "workflow_space")));

        service.repairKnowledgeStatusColumns("MySQL");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.atLeastOnce()).execute(sqlCaptor.capture());
        assertThat(sqlCaptor.getAllValues()).anyMatch(sql -> sql.contains("ADD COLUMN `workflow_space_code`"));
        assertThat(sqlCaptor.getAllValues()).anyMatch(sql -> sql.contains("INSERT INTO `workflow_space`"));
    }

    @Test
    void skipsMissingTablesWithoutFailingStartup() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        KnowledgeSchemaRepairService service = new KnowledgeSchemaRepairService(jdbcTemplate);
        doThrow(new DataAccessResourceFailureException("missing table"))
                .when(jdbcTemplate).queryForList("SHOW COLUMNS FROM `knowledge_base` LIKE 'status'");
        doThrow(new DataAccessResourceFailureException("missing table"))
                .when(jdbcTemplate).queryForList("SHOW COLUMNS FROM `knowledge_document` LIKE 'status'");

        service.repairKnowledgeStatusColumns("MySQL");

        verify(jdbcTemplate).queryForList("SHOW COLUMNS FROM `knowledge_base` LIKE 'status'");
        verify(jdbcTemplate).queryForList("SHOW COLUMNS FROM `knowledge_document` LIKE 'status'");
        verify(jdbcTemplate, never()).execute(anyString());
    }

    private void assertStatusColumnDefinition(Class<?> entityClass) throws Exception {
        Field status = entityClass.getDeclaredField("status");
        Column column = status.getAnnotation(Column.class);

        assertThat(column.columnDefinition()).isEqualTo("VARCHAR(20)");
    }
}
