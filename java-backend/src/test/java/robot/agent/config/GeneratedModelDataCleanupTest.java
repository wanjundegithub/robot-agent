package robot.agent.config;

import org.junit.jupiter.api.Test;
import robot.agent.model.LlmModelRecord;
import robot.agent.model.LlmProviderConfig;
import robot.agent.repository.LlmModelRecordRepository;
import robot.agent.repository.LlmProviderConfigRepository;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeneratedModelDataCleanupTest {

    private final LlmProviderConfigRepository providerRepository = mock(LlmProviderConfigRepository.class);
    private final LlmModelRecordRepository modelRecordRepository = mock(LlmModelRecordRepository.class);
    private final GeneratedModelDataCleanup cleanup = new GeneratedModelDataCleanup(providerRepository, modelRecordRepository);

    @Test
    void runDeletesOnlyGeneratedOpenAiModelRecords() {
        LlmModelRecord generatedRecord = modelRecord(
                "general-chat-v1",
                "通用对话模型",
                "openai-compatible-prod",
                "openai",
                "gpt-4o-mini",
                "system"
        );
        LlmModelRecord doubaoRecord = modelRecord(
                "structured-extraction-v1",
                "豆包大模型",
                "doubao-provider",
                "doubao",
                "doubao-pro-32k",
                "demo-admin"
        );
        LlmProviderConfig generatedProvider = generatedProvider();
        when(modelRecordRepository.findByModelCodeIn(anyCollection())).thenReturn(List.of(generatedRecord, doubaoRecord));
        when(providerRepository.findByProviderCode("openai-compatible-prod")).thenReturn(Optional.of(generatedProvider));
        when(modelRecordRepository.countByProviderCode("openai-compatible-prod")).thenReturn(0L);

        cleanup.run(null);

        verify(modelRecordRepository).deleteAll(List.of(generatedRecord));
        verify(providerRepository).delete(generatedProvider);
    }

    @Test
    void runKeepsUserConfiguredDoubaoProvider() {
        LlmProviderConfig doubaoProvider = new LlmProviderConfig();
        doubaoProvider.setProviderCode("openai-compatible-prod");
        doubaoProvider.setProviderName("豆包大模型");
        doubaoProvider.setProviderType("doubao");
        doubaoProvider.setBaseUrl("https://ark.cn-beijing.volces.com/api/v3");
        when(modelRecordRepository.findByModelCodeIn(anyCollection())).thenReturn(List.of());
        when(providerRepository.findByProviderCode("openai-compatible-prod")).thenReturn(Optional.of(doubaoProvider));

        cleanup.run(null);

        verify(modelRecordRepository, never()).deleteAll(anyCollection());
        verify(providerRepository, never()).delete(doubaoProvider);
    }

    private LlmModelRecord modelRecord(
            String modelCode,
            String modelName,
            String providerCode,
            String provider,
            String upstreamModelCode,
            String createdBy
    ) {
        LlmModelRecord record = new LlmModelRecord();
        record.setModelCode(modelCode);
        record.setModelName(modelName);
        record.setProviderCode(providerCode);
        record.setProvider(provider);
        record.setProviderType(provider);
        record.setUpstreamModelCode(upstreamModelCode);
        record.setCreatedBy(createdBy);
        return record;
    }

    private LlmProviderConfig generatedProvider() {
        LlmProviderConfig provider = new LlmProviderConfig();
        provider.setProviderCode("openai-compatible-prod");
        provider.setProviderName("Default OpenAI Compatible Provider");
        provider.setProviderType("openai");
        provider.setBaseUrl("https://api1.oai1.online/v1");
        provider.setCreatedBy("system");
        return provider;
    }
}
