package robot.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import robot.agent.apicenter.repository.ApiItemRepository;
import robot.agent.config.DefaultModelProperties;
import robot.agent.dto.request.UpsertModelRecordRequest;
import robot.agent.model.LlmModelRecord;
import robot.agent.model.LlmProviderConfig;
import robot.agent.repository.LlmModelRecordRepository;
import robot.agent.repository.LlmProviderConfigRepository;
import robot.agent.repository.WorkflowVersionRepository;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private ModelConfigService modelConfigService;

    @BeforeEach
    void setUp() {
        defaultModelProperties = new DefaultModelProperties();
        modelConfigService = new ModelConfigService(
                providerRepository,
                modelRecordRepository,
                workflowVersionRepository,
                apiItemRepository,
                new ObjectMapper(),
                accessControlService,
                auditService,
                unifiedModelService,
                defaultModelProperties
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
}
