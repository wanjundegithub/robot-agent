package robot.agent.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgePropertiesTest {

    @Test
    void defaultsMatchKnowledgeCenterDesign() {
        KnowledgeProperties properties = new KnowledgeProperties();

        assertThat(properties.getStorage().getType()).isEqualTo("minio");
        assertThat(properties.getStorage().getMinio().getBucket()).isEqualTo("robot-knowledge");
        assertThat(properties.getStorage().getPresignedUrlTtlSeconds()).isEqualTo(300);
        assertThat(properties.getEmbedding().getDefaultModelCode()).isEqualTo("model-431c4581ab84");
        assertThat(properties.getEmbedding().getDefaultUpstreamModel()).isEqualTo("Qwen/Qwen3-Embedding-8B");
        assertThat(properties.getEmbedding().getProviderCode()).isEqualTo("model-431c4581ab84-provider");
        assertThat(properties.getEmbedding().getBaseUrl()).isEqualTo("https://api-inference.modelscope.cn/v1/embeddings");
        assertThat(properties.getEmbedding().getApiKeySecretRef()).isEmpty();
        assertThat(properties.getEmbedding().getEmbeddingPath()).isEqualTo("/embeddings");
        assertThat(properties.getEmbedding().getDimension()).isEqualTo(4096);
        assertThat(properties.getRetrieval().getVectorWeight()).isEqualTo(0.7d);
        assertThat(properties.getRetrieval().getKeywordWeight()).isEqualTo(0.3d);
        assertThat(properties.getRoute().getIntentPrimaryThreshold()).isEqualTo(0.75d);
        assertThat(properties.getRoute().getKnowledgePrimaryThreshold()).isEqualTo(0.65d);
    }
}
