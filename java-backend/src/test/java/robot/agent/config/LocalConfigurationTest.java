package robot.agent.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class LocalConfigurationTest {

    @Test
    void applicationConfiguration_usesLocalFilesInsteadOfNacos() throws IOException {
        String applicationYaml = readProjectFile("src/main/resources/application.yml");

        assertThat(applicationYaml.toLowerCase(Locale.ROOT)).doesNotContain("nacos");
        assertThat(applicationYaml).contains("python:");
        assertThat(applicationYaml).contains("spring:");
        assertThat(applicationYaml).contains("datasource:");
        assertThat(applicationYaml).contains("robot:");
        assertThat(applicationYaml).contains("gateway:");
    }

    @Test
    void applicationConfiguration_definesDefaultModelAtRobotRoot() throws IOException {
        String applicationYaml = readProjectFile("src/main/resources/application.yml");
        String normalizedYaml = applicationYaml.replace("\r\n", "\n");

        assertThat(normalizedYaml).contains("\n  model:\n    default:\n");
        assertThat(normalizedYaml).doesNotContain("\n    robot:\n      model:\n");
        assertThat(normalizedYaml).contains("knowledge:");
        assertThat(normalizedYaml).contains("storage:");
        assertThat(normalizedYaml).contains("minio:");
    }

    @Test
    void buildConfiguration_doesNotPullNacosDependencies() throws IOException {
        String pomXml = readProjectFile("pom.xml");

        assertThat(pomXml.toLowerCase(Locale.ROOT)).doesNotContain("nacos");
        assertThat(pomXml.toLowerCase(Locale.ROOT)).doesNotContain("spring-cloud-alibaba");
    }

    @Test
    void localDockerCompose_doesNotStartNacos() throws IOException {
        String dockerCompose = readProjectFile("../docker-compose.yml");

        assertThat(dockerCompose.toLowerCase(Locale.ROOT)).doesNotContain("nacos");
        assertThat(dockerCompose).contains("robot-agent-minio");
        assertThat(dockerCompose).contains("minio-data");
    }

    private String readProjectFile(String relativePath) throws IOException {
        Path projectRoot = Path.of(System.getProperty("user.dir"));
        return Files.readString(projectRoot.resolve(relativePath), StandardCharsets.UTF_8);
    }
}
