package robot.agent.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class DataSqlSeedTest {

    @Test
    void dataSqlDoesNotSeedDemoKnowledgeSpace() throws Exception {
        byte[] bytes = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream("data.sql"),
                "data.sql must exist"
        ).readAllBytes();
        String dataSql = new String(bytes, StandardCharsets.UTF_8);
        String removedKbCode = "flight_policy" + "_kb";
        String removedKbName = "Flight Policy" + " KB";

        assertThat(dataSql).doesNotContain(removedKbCode);
        assertThat(dataSql).doesNotContain(removedKbName);
        assertThat(dataSql).doesNotContain("INSERT INTO knowledge_base");
        assertThat(dataSql).doesNotContain("INSERT INTO knowledge_version");
    }
}
