package robot.agent.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgePropertiesTest {

    @Test
    void defaultsMatchKnowledgeCenterDesign() {
        KnowledgeProperties properties = new KnowledgeProperties();

        assertThat(properties.getStorage().getType()).isEqualTo("minio");
        assertThat(properties.getStorage().getMinio().getBucket()).isEqualTo("robot-knowledge");
        assertThat(properties.getStorage().getPresignedUrlTtlSeconds()).isEqualTo(300);
        assertThat(properties.getEmbedding().getDefaultModelCode()).isEqualTo("model-431c4581ab84");
        assertThat(properties.getRetrieval().getVectorWeight()).isEqualTo(0.7d);
        assertThat(properties.getRetrieval().getKeywordWeight()).isEqualTo(0.3d);
        assertThat(properties.getRoute().getIntentPrimaryThreshold()).isEqualTo(0.75d);
        assertThat(properties.getRoute().getKnowledgePrimaryThreshold()).isEqualTo(0.65d);
    }

    @Test
    void embeddingPropertiesOnlyExposeModelCode() {
        assertThat(Arrays.stream(KnowledgeProperties.Embedding.class.getDeclaredFields())
                .map(Field::getName)
                .toList())
                .containsExactly("defaultModelCode");
    }
}
