package robot.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.server.ResponseStatusException;
import robot.agent.dto.request.UpsertModelRecordRequest;
import robot.agent.model.CapabilityAuthConfig;
import robot.agent.model.CapabilityGroupSnapshot;
import robot.agent.model.CapabilityItem;
import robot.agent.model.LlmModelRecord;
import robot.agent.model.LlmProviderConfig;
import robot.agent.model.WorkflowVersion;
import robot.agent.repository.CapabilityAuthConfigRepository;
import robot.agent.repository.CapabilityGroupSnapshotRepository;
import robot.agent.repository.CapabilityItemRepository;
import robot.agent.repository.LlmModelRecordRepository;
import robot.agent.repository.LlmProviderConfigRepository;
import robot.agent.repository.WorkflowVersionRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelConfigServiceTest {

    private static final LocalDateTime FIXED_UPDATED_AT = LocalDateTime.of(2026, 4, 27, 10, 30, 0);

    private final LlmProviderConfigRepository providerRepository = mock(LlmProviderConfigRepository.class);
    private final LlmModelRecordRepository modelRecordRepository = mock(LlmModelRecordRepository.class);
    private final WorkflowVersionRepository workflowVersionRepository = mock(WorkflowVersionRepository.class);
    private final CapabilityItemRepository capabilityItemRepository = mock(CapabilityItemRepository.class);
    private final CapabilityGroupSnapshotRepository capabilityGroupSnapshotRepository = mock(CapabilityGroupSnapshotRepository.class);
    private final CapabilityAuthConfigRepository capabilityAuthConfigRepository = mock(CapabilityAuthConfigRepository.class);
    private final AccessControlService accessControlService = mock(AccessControlService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final UnifiedModelService unifiedModelService = mock(UnifiedModelService.class);
    private final ModelConfigService modelConfigService = new ModelConfigService(
            providerRepository,
            modelRecordRepository,
            workflowVersionRepository,
            capabilityItemRepository,
            capabilityGroupSnapshotRepository,
            capabilityAuthConfigRepository,
            new ObjectMapper(),
            accessControlService,
            auditService,
            unifiedModelService
    );

    @BeforeEach
    void setUp() {
        doNothing().when(accessControlService).requireWorkflowAdminAction(anyString(), anyLong(), anyString(), anyString());
        doNothing().when(auditService).logAction(anyLong(), anyString(), anyString(), anyString(), anyString(), any(), anyInt());
        when(workflowVersionRepository.findAll()).thenReturn(List.of());
        when(capabilityItemRepository.findAll()).thenReturn(List.of());
        when(capabilityGroupSnapshotRepository.findAll()).thenReturn(List.of());
        when(capabilityAuthConfigRepository.findAll()).thenReturn(List.of());
    }

    @Test
    void springContextCanInstantiateModelConfigServiceBean() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(LlmProviderConfigRepository.class, () -> providerRepository);
            context.registerBean(LlmModelRecordRepository.class, () -> modelRecordRepository);
            context.registerBean(WorkflowVersionRepository.class, () -> workflowVersionRepository);
            context.registerBean(CapabilityItemRepository.class, () -> capabilityItemRepository);
            context.registerBean(CapabilityGroupSnapshotRepository.class, () -> capabilityGroupSnapshotRepository);
            context.registerBean(CapabilityAuthConfigRepository.class, () -> capabilityAuthConfigRepository);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(AccessControlService.class, () -> accessControlService);
            context.registerBean(AuditService.class, () -> auditService);
            context.registerBean(UnifiedModelService.class, () -> unifiedModelService);
            context.registerBean(ModelConfigService.class);

            assertThatCode(context::refresh).doesNotThrowAnyException();
            assertThat(context.getBean(ModelConfigService.class)).isNotNull();
        }
    }

    @Test
    void listModelRecordsRequestsUpdatedAtDescSortAndReturnsSimplifiedEnvelope() {
        PageRequest repositoryPageFixture = PageRequest.of(0, 10, Sort.by(Sort.Order.desc("updatedAt")));
        when(modelRecordRepository.search(eq("gpt-4o"), eq(null), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(modelRecord(1L, "chat-main", "通用对话模型", "openai", "gpt-4o-mini")), repositoryPageFixture, 1));

        Map<String, Object> page = modelConfigService.getModelRecords("gpt-4o", null, null, 0, 10);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(modelRecordRepository).search(eq("gpt-4o"), eq(null), eq(null), pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();

        assertThat(page.get("page")).isEqualTo(0);
        assertThat(page.get("page_size")).isEqualTo(10);
        assertThat(((Number) page.get("total")).longValue()).isEqualTo(1L);
        assertThat(pageable.getSort().getOrderFor("updatedAt")).isNotNull();
        assertThat(pageable.getSort().getOrderFor("updatedAt").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat((List<?>) page.get("items")).hasSize(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) ((List<?>) page.get("items")).get(0);
        assertThat(first.get("id")).isEqualTo(1L);
        assertThat(first.get("custom_model_name")).isEqualTo("通用对话模型");
        assertThat(first.get("provider")).isEqualTo("openai");
        assertThat(first.get("model_name")).isEqualTo("gpt-4o-mini");
        assertThat(first.get("base_url")).isEqualTo("https://api.example.com/v1");
        assertThat(first).doesNotContainKeys("model_code", "provider_code", "upstream_model_code", "capabilities", "default_system_prompt", "default_options", "enabled");
    }

    @Test
    void testSimpleModelConnectionDelegatesToDirectHttpCall() {
        UpsertModelRecordRequest request = new UpsertModelRecordRequest();
        request.setCustomModelName("通用对话模型");
        request.setProvider("openai");
        request.setModelName("gpt-4o-mini");
        request.setApiKey("sk-demo");
        request.setBaseUrl("https://api.example.com/v1");

        when(unifiedModelService.invokeDirectChat(
                eq("openai"),
                eq("https://api.example.com/v1"),
                eq("sk-demo"),
                eq("gpt-4o-mini"),
                any(),
                eq(Map.of())
        )).thenReturn(new UnifiedModelResult(
                "draft-general-chat",
                "draft-provider",
                "gpt-4o-mini",
                "connectivity ok",
                Map.of("total_tokens", 20),
                Map.of()
        ));

        Map<String, Object> result = modelConfigService.testSimpleModelConnection("demo-admin", request);

        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("provider")).isEqualTo("openai");
        assertThat(result.get("model_name")).isEqualTo("gpt-4o-mini");
        assertThat(result.get("answer")).isEqualTo("connectivity ok");
    }

    @Test
    void deleteProviderRejectsWhenModelRecordsStillReferenceIt() {
        when(providerRepository.findByProviderCode("provider-a")).thenReturn(Optional.of(provider("provider-a")));
        when(modelRecordRepository.countByProviderCode("provider-a")).thenReturn(2L);

        assertThatThrownBy(() -> modelConfigService.deleteProviderConfig("demo-admin", "provider-a"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException exception = (ResponseStatusException) error;
                    assertThat(exception.getStatusCode().is4xxClientError()).isTrue();
                    assertThat(exception.getReason()).contains("provider is still referenced");
                });
    }

    @Test
    void resolveRoutingModelCodeReturnsNullWhenExplicitRoutingModelCodeMissing() {
        String routingModelCode = modelConfigService.resolveRoutingModelCode(List.of(
                Map.of("config", Map.of("llm_defaults", Map.of("model_code", "chat-main")))
        ));

        assertThat(routingModelCode).isNull();
    }

    @Test
    void buildRuntimeBundleCollectsExplicitRuntimeModelCodesOnly() {
        when(modelRecordRepository.findByModelCodeIn(any(Collection.class))).thenReturn(List.of());

        modelConfigService.buildRuntimeBundle(List.of(
                Map.of(
                        "config", Map.of(
                                "routing_model_code", "router-main",
                                "llm_defaults", Map.of("model_code", "default-chat")
                        ),
                        "nodes", Map.of(
                                "chat-node", Map.of(
                                        "config", Map.of("model_code", "chat-main")
                                )
                        )
                )
        ), "router-main");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> modelCodeCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(modelRecordRepository).findByModelCodeIn(modelCodeCaptor.capture());
        assertThat(modelCodeCaptor.getValue()).contains("router-main", "default-chat", "chat-main");
    }

    @Test
    void deleteModelRecordRejectsWhenWorkflowStillReferencesHiddenModelCode() {
        LlmModelRecord record = modelRecord(10L, "chat-main", "通用对话模型", "openai", "gpt-4o-mini");
        when(modelRecordRepository.findById(10L)).thenReturn(Optional.of(record));
        when(workflowVersionRepository.findAll()).thenReturn(List.of(workflowVersion(
                "travel_assistant",
                "v1",
                """
                        {"nodes":{"node_1":{"config":{"model_code":"chat-main"}}}}
                        """,
                "{}"
        )));

        assertThatThrownBy(() -> modelConfigService.deleteModelRecord("demo-admin", 10L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException exception = (ResponseStatusException) error;
                    assertThat(exception.getStatusCode().is4xxClientError()).isTrue();
                    assertThat(exception.getReason()).contains("workflow_definition:travel_assistant@v1");
                });
    }

    @Test
    void deleteModelRecordRejectsWhenCapabilityConfigStillReferencesHiddenModelCode() {
        LlmModelRecord record = modelRecord(10L, "chat-main", "通用对话模型", "openai", "gpt-4o-mini");
        when(modelRecordRepository.findById(10L)).thenReturn(Optional.of(record));
        when(capabilityItemRepository.findAll()).thenReturn(List.of(capabilityItem(
                "ops",
                "search",
                """
                        {"model_code":"chat-main"}
                        """
        )));
        when(capabilityGroupSnapshotRepository.findAll()).thenReturn(List.of(capabilityGroupSnapshot(
                "ops",
                "2026-04-26",
                """
                        {"runtime":{"routing_model_code":"chat-main"}}
                        """
        )));
        when(capabilityAuthConfigRepository.findAll()).thenReturn(List.of(capabilityAuthConfig(
                "ops",
                """
                        {"model_code":"chat-main"}
                        """
        )));

        assertThatThrownBy(() -> modelConfigService.deleteModelRecord("demo-admin", 10L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException exception = (ResponseStatusException) error;
                    assertThat(exception.getStatusCode().is4xxClientError()).isTrue();
                    assertThat(exception.getReason()).contains("capability_definition:ops/search");
                    assertThat(exception.getReason()).contains("capability_snapshot:ops@2026-04-26");
                    assertThat(exception.getReason()).contains("capability_auth_config:ops");
                });
    }

    private LlmModelRecord modelRecord(Long id, String modelCode, String customModelName, String provider, String modelName) {
        LlmModelRecord record = new LlmModelRecord();
        record.setId(id);
        record.setModelCode(modelCode);
        record.setModelName(customModelName);
        record.setProviderCode("provider-a");
        record.setProviderType(provider);
        record.setUpstreamModelCode(modelName);
        record.setApiKey("sk-demo");
        record.setBaseUrl("https://api.example.com/v1");
        record.setEnabled(true);
        record.setUpdatedAt(FIXED_UPDATED_AT);
        return record;
    }

    private LlmProviderConfig provider(String providerCode) {
        LlmProviderConfig provider = new LlmProviderConfig();
        provider.setProviderCode(providerCode);
        provider.setProviderName("Provider A");
        provider.setProviderType("openai");
        provider.setBaseUrl("https://api.example.com");
        provider.setEnabled(true);
        return provider;
    }

    private WorkflowVersion workflowVersion(String workflowCode, String version, String definition, String config) {
        WorkflowVersion workflowVersion = new WorkflowVersion();
        workflowVersion.setWorkflowCode(workflowCode);
        workflowVersion.setVersion(version);
        workflowVersion.setDefinition(definition);
        workflowVersion.setConfig(config);
        return workflowVersion;
    }

    private CapabilityItem capabilityItem(String groupCode, String capabilityCode, String definitionJson) {
        CapabilityItem capabilityItem = new CapabilityItem();
        capabilityItem.setGroupCode(groupCode);
        capabilityItem.setCapabilityCode(capabilityCode);
        capabilityItem.setDefinitionJson(definitionJson);
        return capabilityItem;
    }

    private CapabilityGroupSnapshot capabilityGroupSnapshot(String groupCode, String snapshotVersion, String snapshotPayload) {
        CapabilityGroupSnapshot snapshot = new CapabilityGroupSnapshot();
        snapshot.setGroupCode(groupCode);
        snapshot.setSnapshotVersion(snapshotVersion);
        snapshot.setSnapshotPayload(snapshotPayload);
        return snapshot;
    }

    private CapabilityAuthConfig capabilityAuthConfig(String groupCode, String configJson) {
        CapabilityAuthConfig authConfig = new CapabilityAuthConfig();
        authConfig.setGroupCode(groupCode);
        authConfig.setConfigJson(configJson);
        return authConfig;
    }
}
