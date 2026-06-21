package robot.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import robot.agent.apicenter.repository.ApiItemRepository;
import robot.agent.config.DefaultModelProperties;
import robot.agent.config.KnowledgeProperties;
import robot.agent.dto.request.UpsertModelRecordRequest;
import robot.agent.model.LlmModelRecord;
import robot.agent.model.LlmProviderConfig;
import robot.agent.repository.LlmModelRecordRepository;
import robot.agent.repository.LlmProviderConfigRepository;
import robot.agent.repository.WorkflowVersionRepository;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelConfigServiceTest {

    @Mock
    private LlmProviderConfigRepository providerRepository;

    @Mock
    private LlmModelRecordRepository modelRecordRepository;

    @Mock
    private WorkflowVersionRepository workflowVersionRepository;

    @Mock
    private ApiItemRepository apiItemRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private AuditService auditService;

    @Mock
    private UnifiedModelService unifiedModelService;

    private DefaultModelProperties defaultModelProperties;
    private KnowledgeProperties knowledgeProperties;
    private ModelConfigService modelConfigService;

    @BeforeEach
    void setUp() {
        defaultModelProperties = new DefaultModelProperties();
        knowledgeProperties = new KnowledgeProperties();
        modelConfigService = new ModelConfigService(
                providerRepository,
                modelRecordRepository,
                workflowVersionRepository,
                apiItemRepository,
                new ObjectMapper(),
                accessControlService,
                auditService,
                unifiedModelService,
                defaultModelProperties,
                knowledgeProperties
        );
    }

    @Test
    void buildDefaultRuntimeBundle_prefersConfiguredDefaultModelCode() {
        defaultModelProperties.setModelCode("local-default-chat");
        LlmModelRecord modelRecord = modelRecord("local-default-chat", "doubao-provider");
        LlmProviderConfig provider = provider("doubao-provider");

        when(modelRecordRepository.findByModelCode("local-default-chat")).thenReturn(Optional.of(modelRecord));
        when(providerRepository.findByProviderCode("doubao-provider")).thenReturn(Optional.of(provider));

        ModelConfigService.RuntimeModelBundle bundle = modelConfigService.buildDefaultRuntimeBundle();

        assertThat(bundle.modelRecords()).extracting(item -> item.get("model_code")).containsExactly("local-default-chat");
        assertThat(bundle.providerConfigs()).extracting(item -> item.get("provider_code")).containsExactly("doubao-provider");
        verify(providerRepository, never()).findByEnabledTrueOrderByProviderCodeAsc();
    }

    @Test
    void resolveConfiguredPurposeModelCode_prefersPurposeSpecificModelCode() {
        defaultModelProperties.setModelCode("default-chat");
        defaultModelProperties.setPurposeModelCodes(Map.of("slot-extraction", "slot-chat"));

        assertThat(modelConfigService.resolveConfiguredPurposeModelCode("slot-extraction")).isEqualTo("slot-chat");
        assertThat(modelConfigService.resolveConfiguredPurposeModelCode("welcome")).isEqualTo("default-chat");
    }

    @Test
    void buildRuntimeBundleForModelUsesConfiguredDatabaseEmbeddingModel() {
        LlmModelRecord modelRecord = modelRecord("model-431c4581ab84", "model-431c4581ab84-provider");
        modelRecord.setModelName("Qwen/Qwen3-Embedding-8B");
        modelRecord.setUpstreamModelCode("Qwen/Qwen3-Embedding-8B");
        modelRecord.setDefaultOptionsJson("{\"input\":\"hello\",\"encoding_format\":\"float\"}");
        LlmProviderConfig provider = provider("model-431c4581ab84-provider");
        provider.setProviderType("openai_compatible");
        provider.setBaseUrl("https://api-inference.modelscope.cn/v1/embeddings");
        provider.setApiKeySecretRef("ms-db-secret");

        when(modelRecordRepository.findByModelCode("model-431c4581ab84")).thenReturn(Optional.of(modelRecord));
        when(providerRepository.findByProviderCode("model-431c4581ab84-provider")).thenReturn(Optional.of(provider));

        ModelConfigService.RuntimeModelBundle bundle = modelConfigService.buildRuntimeBundleForModel("model-431c4581ab84");

        assertThat(bundle.providerConfigs()).extracting(item -> item.get("provider_code")).containsExactly("model-431c4581ab84-provider");
        assertThat(bundle.providerConfigs().get(0)).containsEntry("api_key_secret_ref", "ms-db-secret");
        assertThat(bundle.modelRecords()).extracting(item -> item.get("model_code")).containsExactly("model-431c4581ab84");
        assertThat(bundle.modelRecords().get(0)).containsEntry("upstream_model_code", "Qwen/Qwen3-Embedding-8B");
    }

    @Test
    void buildRuntimeBundleForModelDoesNotSynthesizeEmbeddingFallbackWhenDatabaseModelMissing() {
        knowledgeProperties.getEmbedding().setDefaultModelCode("model-431c4581ab84");
        when(modelRecordRepository.findByModelCode("model-431c4581ab84")).thenReturn(Optional.empty());

        ModelConfigService.RuntimeModelBundle bundle = modelConfigService.buildRuntimeBundleForModel("model-431c4581ab84");

        assertThat(bundle.providerConfigs()).isEmpty();
        assertThat(bundle.modelRecords()).isEmpty();
    }

    @Test
    void testSimpleModelConnectionPassesDefaultOptionsToDirectChat() {
        UpsertModelRecordRequest request = new UpsertModelRecordRequest();
        request.setCustomModelName("Qwen/Qwen3-8B");
        request.setProvider("custom");
        request.setModelName("Qwen/Qwen3-8B");
        request.setApiKey("test-secret");
        request.setBaseUrl("https://api-inference.modelscope.cn/v1/chat/completions");
        request.setDefaultOptions(Map.of("stream", true, "enable_thinking", true));
        when(unifiedModelService.invokeDirectChat(
                eq("custom"),
                eq("https://api-inference.modelscope.cn/v1/chat/completions"),
                eq("test-secret"),
                eq("Qwen/Qwen3-8B"),
                any(),
                any()
        )).thenReturn(new UnifiedModelResult(
                "draft-custom",
                "draft-provider",
                "Qwen/Qwen3-8B",
                "ok",
                Map.of(),
                Map.of()
        ));

        Map<String, Object> response = modelConfigService.testSimpleModelConnection("demo-admin", request);

        assertThat(response).containsEntry("ok", true);
        verify(unifiedModelService).invokeDirectChat(
                eq("custom"),
                eq("https://api-inference.modelscope.cn/v1/chat/completions"),
                eq("test-secret"),
                eq("Qwen/Qwen3-8B"),
                any(),
                eq(Map.of("stream", true, "enable_thinking", true))
        );
    }

    @Test
    void saveModelRecordRequiresUserProvidedModelCodeAndPersistsIt() {
        UpsertModelRecordRequest request = upsertRequest("general-chat-v1");
        when(modelRecordRepository.findByModelCode("general-chat-v1")).thenReturn(Optional.empty());
        when(providerRepository.findByProviderCode("general-chat-v1-provider")).thenReturn(Optional.empty());
        when(modelRecordRepository.save(any(LlmModelRecord.class))).thenAnswer(invocation -> {
            LlmModelRecord saved = invocation.getArgument(0);
            saved.setId(7L);
            return saved;
        });

        Map<String, Object> response = modelConfigService.saveModelRecord("demo-admin", request);

        assertThat(response).containsEntry("model_code", "general-chat-v1");
        verify(modelRecordRepository).save(argThat(record ->
                "general-chat-v1".equals(record.getModelCode())
                        && "general-chat-v1-provider".equals(record.getProviderCode())
        ));
    }

    @Test
    void saveModelRecordRejectsMissingModelCode() {
        UpsertModelRecordRequest request = upsertRequest(null);

        assertThatThrownBy(() -> modelConfigService.saveModelRecord("demo-admin", request))
                .hasMessageContaining("model_code is required");

        verify(modelRecordRepository, never()).save(any());
    }

    @Test
    void saveModelRecordRejectsDuplicateModelCode() {
        UpsertModelRecordRequest request = upsertRequest("general-chat-v1");
        when(modelRecordRepository.findByModelCode("general-chat-v1"))
                .thenReturn(Optional.of(modelRecord("general-chat-v1", "general-chat-v1-provider")));

        assertThatThrownBy(() -> modelConfigService.saveModelRecord("demo-admin", request))
                .hasMessageContaining("Model record already exists: general-chat-v1");

        verify(modelRecordRepository, never()).save(any());
    }

    @Test
    void updateModelRecordFindsByModelCodeAndRejectsBodyCodeMismatch() {
        UpsertModelRecordRequest request = upsertRequest("other-chat-v1");

        assertThatThrownBy(() -> modelConfigService.updateModelRecord("demo-admin", "general-chat-v1", request))
                .hasMessageContaining("model_code cannot be changed");

        verify(modelRecordRepository, never()).save(any());
    }

    @Test
    void updateModelRecordFindsByModelCodeAndKeepsCodeStable() {
        UpsertModelRecordRequest request = upsertRequest("general-chat-v1");
        request.setCustomModelName("通用对话模型-已编辑");
        LlmModelRecord existing = modelRecord("general-chat-v1", "general-chat-v1-provider");
        existing.setApiKey("old-secret");
        existing.setBaseUrl("https://old.example.com/v1");
        when(modelRecordRepository.findByModelCode("general-chat-v1")).thenReturn(Optional.of(existing));
        when(providerRepository.findByProviderCode("general-chat-v1-provider")).thenReturn(Optional.empty());
        when(modelRecordRepository.save(any(LlmModelRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> response = modelConfigService.updateModelRecord("demo-admin", "general-chat-v1", request);

        assertThat(response).containsEntry("model_code", "general-chat-v1");
        assertThat(response).containsEntry("custom_model_name", "通用对话模型-已编辑");
        verify(modelRecordRepository).findByModelCode("general-chat-v1");
        verify(modelRecordRepository).save(argThat(record -> "general-chat-v1".equals(record.getModelCode())));
    }

    @Test
    void deleteModelRecordFindsByModelCode() {
        LlmModelRecord existing = modelRecord("general-chat-v1", "general-chat-v1-provider");
        when(modelRecordRepository.findByModelCode("general-chat-v1")).thenReturn(Optional.of(existing));
        when(workflowVersionRepository.findAll()).thenReturn(java.util.List.of());
        when(apiItemRepository.findAll()).thenReturn(java.util.List.of());

        modelConfigService.deleteModelRecord("demo-admin", "general-chat-v1");

        verify(modelRecordRepository).delete(existing);
    }

    @Test
    void listModelRecordsDoesNotExposeFullApiKeyButDetailDoes() {
        LlmModelRecord record = modelRecord("general-chat-v1", "general-chat-v1-provider");
        record.setId(7L);
        record.setApiKey("sk-secret-value");
        org.springframework.data.domain.PageRequest pageRequest = org.springframework.data.domain.PageRequest.of(0, 20, org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Order.desc("updatedAt")));
        when(modelRecordRepository.search(null, null, null, pageRequest))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(record), pageRequest, 1));
        when(providerRepository.findByProviderCodeIn(java.util.Set.of("general-chat-v1-provider")))
                .thenReturn(java.util.List.of());
        when(modelRecordRepository.findByModelCode("general-chat-v1")).thenReturn(Optional.of(record));

        Map<String, Object> page = modelConfigService.getModelRecords(null, null, null, 0, 20);
        @SuppressWarnings("unchecked")
        Map<String, Object> listItem = (Map<String, Object>) ((java.util.List<?>) page.get("items")).get(0);
        Map<String, Object> detail = modelConfigService.getModelRecord("general-chat-v1");

        assertThat(listItem).containsEntry("model_code", "general-chat-v1");
        assertThat(listItem).doesNotContainKey("api_key");
        assertThat(listItem).containsEntry("api_key_configured", true);
        assertThat(listItem).containsEntry("api_key_masked", "sk-s****alue");
        assertThat(detail).containsEntry("api_key", "sk-secret-value");
    }

    @Test
    void testSimpleModelConnectionRoutesEmbeddingDraftToDirectEmbedding() {
        UpsertModelRecordRequest request = new UpsertModelRecordRequest();
        request.setCustomModelName("Qwen/Qwen3-Embedding-8B");
        request.setProvider("custom");
        request.setModelName("Qwen/Qwen3-Embedding-8B");
        request.setApiKey("test-secret");
        request.setBaseUrl("https://api-inference.modelscope.cn/v1/embeddings");
        request.setDefaultOptions(Map.of("input", "hello", "encoding_format", "float"));
        when(unifiedModelService.invokeDirectEmbedding(
                eq("custom"),
                eq("https://api-inference.modelscope.cn/v1/embeddings"),
                eq("test-secret"),
                eq("Qwen/Qwen3-Embedding-8B"),
                any()
        )).thenReturn(new UnifiedModelResult(
                "draft-custom",
                "draft-provider",
                "Qwen/Qwen3-Embedding-8B",
                "Embedding call succeeded: 1 vector(s), dimension 3",
                Map.of("total_tokens", 2),
                Map.of()
        ));

        Map<String, Object> response = modelConfigService.testSimpleModelConnection("demo-admin", request);

        assertThat(response).containsEntry("ok", true);
        assertThat(response).containsEntry("model_type", "embedding");
        assertThat(response).containsEntry("answer", "Embedding call succeeded: 1 vector(s), dimension 3");
        verify(unifiedModelService).invokeDirectEmbedding(
                eq("custom"),
                eq("https://api-inference.modelscope.cn/v1/embeddings"),
                eq("test-secret"),
                eq("Qwen/Qwen3-Embedding-8B"),
                eq(Map.of("input", "hello", "encoding_format", "float"))
        );
        verify(unifiedModelService, never()).invokeDirectChat(any(), any(), any(), any(), any(), any());
    }

    private LlmModelRecord modelRecord(String modelCode, String providerCode) {
        LlmModelRecord modelRecord = new LlmModelRecord();
        modelRecord.setModelCode(modelCode);
        modelRecord.setModelName(modelCode);
        modelRecord.setProviderCode(providerCode);
        modelRecord.setUpstreamModelCode("doubao-seed");
        modelRecord.setEnabled(true);
        return modelRecord;
    }

    private LlmProviderConfig provider(String providerCode) {
        LlmProviderConfig provider = new LlmProviderConfig();
        provider.setProviderCode(providerCode);
        provider.setProviderName(providerCode);
        provider.setProviderType("doubao");
        provider.setBaseUrl("https://ark.example.com/api/v3");
        provider.setApiKeySecretRef("env:ARK_API_KEY");
        provider.setEnabled(true);
        return provider;
    }

    private UpsertModelRecordRequest upsertRequest(String modelCode) {
        UpsertModelRecordRequest request = new UpsertModelRecordRequest();
        request.setModelCode(modelCode);
        request.setCustomModelName("通用对话模型");
        request.setProvider("openai_compatible");
        request.setModelName("gpt-4o-mini");
        request.setApiKey("sk-secret-value");
        request.setBaseUrl("https://api.example.com/v1");
        request.setDefaultOptions(Map.of("temperature", 0.2));
        return request;
    }
}
