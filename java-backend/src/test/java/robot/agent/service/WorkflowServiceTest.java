package robot.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import robot.agent.model.Workflow;
import robot.agent.model.WorkflowStatus;
import robot.agent.model.WorkflowVersion;
import robot.agent.model.WorkflowVersionStatus;
import robot.agent.dto.request.CreateWorkflowVersionRequest;
import robot.agent.repository.WorkflowRepository;
import robot.agent.repository.WorkflowVersionRepository;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceTest {

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private WorkflowVersionRepository workflowVersionRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private AuditService auditService;

    @Mock
    private PythonClient pythonClient;

    @Mock
    private ModelConfigService modelConfigService;

    private WorkflowService workflowService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        workflowService = new WorkflowService(
                workflowRepository,
                workflowVersionRepository,
                objectMapper,
                accessControlService,
                auditService,
                pythonClient,
                modelConfigService
        );
    }

    @Test
    void buildRuntimeExecutionBundle_fallsBackToDefaultModelWhenWorkflowHasNoModelBinding() throws Exception {
        WorkflowVersion version = publishedVersion(
                "hotel_booking",
                "1.0.0",
                Map.of("model_bindings", Map.of())
        );
        Workflow workflow = new Workflow();
        workflow.setWorkflowCode("hotel_booking");
        workflow.setStatus(WorkflowStatus.PUBLISHED);

        when(workflowVersionRepository.findByWorkflowCodeAndVersion("hotel_booking", "1.0.0"))
                .thenReturn(Optional.of(version));
        when(workflowVersionRepository.findByStatusOrderByCreatedAtDesc(WorkflowVersionStatus.PUBLISHED))
                .thenReturn(List.of(version));
        when(workflowRepository.findByWorkflowCode("hotel_booking"))
                .thenReturn(Optional.of(workflow));
        when(modelConfigService.resolveRoutingModelCode(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(null);
        when(modelConfigService.buildRuntimeBundle(org.mockito.ArgumentMatchers.anyCollection(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new ModelConfigService.RuntimeModelBundle(List.of(), List.of()));
        when(modelConfigService.buildDefaultRuntimeBundle())
                .thenReturn(new ModelConfigService.RuntimeModelBundle(
                        List.of(Map.of("provider_code", "doubao")),
                        List.of(Map.of("model_code", "doubao-chat", "provider_code", "doubao"))
                ));

        WorkflowService.RuntimeExecutionBundle bundle = workflowService.buildRuntimeExecutionBundle("hotel_booking", "1.0.0");

        assertThat(bundle.routingModelCode()).isEqualTo("doubao-chat");
        assertThat(bundle.providerConfigs()).containsExactly(Map.of("provider_code", "doubao"));
        assertThat(bundle.modelRecords()).containsExactly(Map.of("model_code", "doubao-chat", "provider_code", "doubao"));
        assertThat(bundle.workflowConfig()).containsEntry("llm_defaults", Map.of("model_code", "doubao-chat"));
    }

    @Test
    void routeMessage_usesModelFallbackMessageWhenNoWorkflowMatchesHotelRequest() throws Exception {
        WorkflowVersion flightVersion = publishedVersion(
                "flight_booking",
                "1.0.0",
                Map.of("routing_model_code", "route-model"),
                List.of("机票", "航班"),
                List.of("book_flight")
        );
        Workflow flightWorkflow = publishedWorkflow("flight_booking", "1.0.0", "机票预订", "预订航班和机票");

        when(workflowRepository.findByStatusOrderByCreatedAtDesc(WorkflowStatus.PUBLISHED))
                .thenReturn(List.of(flightWorkflow));
        when(workflowVersionRepository.findByWorkflowCodeAndVersion("flight_booking", "1.0.0"))
                .thenReturn(Optional.of(flightVersion));
        when(workflowRepository.findByWorkflowCode("flight_booking"))
                .thenReturn(Optional.of(flightWorkflow));
        when(modelConfigService.resolveRoutingModelCode(anyCollection())).thenReturn("route-model");
        when(modelConfigService.buildRuntimeBundle(anyCollection(), eq("route-model")))
                .thenReturn(new ModelConfigService.RuntimeModelBundle(
                        List.of(Map.of("provider_code", "demo")),
                        List.of(Map.of("model_code", "route-model", "provider_code", "demo"))
                ));
        when(pythonClient.classifyIntent(anyMap())).thenReturn(Mono.just(Map.of(
                "matched", false,
                "confidence", 0.12d,
                "reason", "no available hotel workflow",
                "need_clarification", true,
                "clarification_question", "模型生成：抱歉，当前无法处理酒店预订，您还需要其他帮助吗？"
        )));

        RoutingDecision decision = workflowService.routeMessage("我要预定酒店", null);

        assertThat(decision.decision()).isEqualTo("clarification_required");
        assertThat(decision.workflowCode()).isNull();
        assertThat(decision.workflowVersion()).isNull();
        assertThat(decision.reason()).isEqualTo("llm_no_match");
        assertThat(decision.clarificationQuestion()).isEqualTo("模型生成：抱歉，当前无法处理酒店预订，您还需要其他帮助吗？");

        ArgumentCaptor<Map<String, Object>> requestCaptor = ArgumentCaptor.forClass(Map.class);
        verify(pythonClient).classifyIntent(requestCaptor.capture());
        assertThat(castMap(requestCaptor.getValue().get("system_prompts")))
                .containsKeys("workflow_control", "intent_routing", "workflow_welcome");
    }

    @Test
    void routeMessage_returnsUnsupportedServiceFallbackWhenModelTargetWorkflowIsMissing() throws Exception {
        WorkflowVersion flightVersion = publishedVersion(
                "flight_booking",
                "1.0.0",
                Map.of("routing_model_code", "route-model"),
                List.of("机票", "航班"),
                List.of("book_flight")
        );
        Workflow flightWorkflow = publishedWorkflow("flight_booking", "1.0.0", "机票预订", "预订航班和机票");

        when(workflowRepository.findByStatusOrderByCreatedAtDesc(WorkflowStatus.PUBLISHED))
                .thenReturn(List.of(flightWorkflow));
        when(workflowVersionRepository.findByWorkflowCodeAndVersion("flight_booking", "1.0.0"))
                .thenReturn(Optional.of(flightVersion));
        when(workflowRepository.findByWorkflowCode("flight_booking"))
                .thenReturn(Optional.of(flightWorkflow));
        when(modelConfigService.resolveRoutingModelCode(anyCollection())).thenReturn("route-model");
        when(modelConfigService.buildRuntimeBundle(anyCollection(), eq("route-model")))
                .thenReturn(new ModelConfigService.RuntimeModelBundle(
                        List.of(Map.of("provider_code", "demo")),
                        List.of(Map.of("model_code", "route-model", "provider_code", "demo"))
                ));
        when(pythonClient.classifyIntent(anyMap())).thenReturn(Mono.just(Map.of(
                "matched", true,
                "intent_code", "book_hotel",
                "target_type", "workflow",
                "target_code", "hotel_booking",
                "workflow_code", "hotel_booking",
                "confidence", 0.96d,
                "reason", "hotel intent",
                "clarification_question", "模型生成：酒店预订暂未开通，请问还需要查询航班吗？"
        )));

        RoutingDecision decision = workflowService.routeMessage("我要预定酒店", null);

        assertThat(decision.decision()).isEqualTo("clarification_required");
        assertThat(decision.workflowCode()).isNull();
        assertThat(decision.workflowVersion()).isNull();
        assertThat(decision.reason()).isEqualTo("llm_target_workflow_missing");
        assertThat(decision.clarificationQuestion()).isEqualTo("模型生成：酒店预订暂未开通，请问还需要查询航班吗？");
    }

    @Test
    void routeMessage_usesGenericFallbackWhenModelIsUnavailable() throws Exception {
        WorkflowVersion flightVersion = publishedVersion(
                "flight_booking",
                "1.0.0",
                Map.of("routing_model_code", "route-model"),
                List.of("机票", "航班"),
                List.of("book_flight")
        );
        Workflow flightWorkflow = publishedWorkflow("flight_booking", "1.0.0", "机票预订", "预订航班和机票");

        when(workflowRepository.findByStatusOrderByCreatedAtDesc(WorkflowStatus.PUBLISHED))
                .thenReturn(List.of(flightWorkflow));
        when(workflowVersionRepository.findByWorkflowCodeAndVersion("flight_booking", "1.0.0"))
                .thenReturn(Optional.of(flightVersion));
        when(workflowRepository.findByWorkflowCode("flight_booking"))
                .thenReturn(Optional.of(flightWorkflow));
        when(modelConfigService.resolveRoutingModelCode(anyCollection())).thenReturn("route-model");
        when(modelConfigService.buildRuntimeBundle(anyCollection(), eq("route-model")))
                .thenReturn(new ModelConfigService.RuntimeModelBundle(List.of(), List.of()));

        RoutingDecision decision = workflowService.routeMessage("我要预定酒店", null);

        assertThat(decision.decision()).isEqualTo("clarification_required");
        assertThat(decision.clarificationQuestion()).isEqualTo("抱歉，当前没有匹配的可用服务，您还需要其他服务吗？");
    }

    @Test
    void routeMessage_fallsBackToDefaultRuntimeModelWhenRoutingModelRecordIsMissing() throws Exception {
        WorkflowVersion hotelVersion = publishedVersion(
                "hotel_booking",
                "1.0.0",
                Map.of("routing_model_code", "intent-router"),
                List.of(),
                List.of("book_hotel")
        );
        Workflow hotelWorkflow = publishedWorkflow("hotel_booking", "1.0.0", "预定酒店", "帮助用户预定酒店");

        when(workflowRepository.findByStatusOrderByCreatedAtDesc(WorkflowStatus.PUBLISHED))
                .thenReturn(List.of(hotelWorkflow));
        when(workflowVersionRepository.findByWorkflowCodeAndVersion("hotel_booking", "1.0.0"))
                .thenReturn(Optional.of(hotelVersion));
        when(workflowRepository.findByWorkflowCode("hotel_booking"))
                .thenReturn(Optional.of(hotelWorkflow));
        when(modelConfigService.resolveRoutingModelCode(anyCollection())).thenReturn("intent-router");
        when(modelConfigService.buildRuntimeBundle(anyCollection(), eq("intent-router")))
                .thenReturn(new ModelConfigService.RuntimeModelBundle(List.of(), List.of()));
        when(modelConfigService.buildDefaultRuntimeBundle())
                .thenReturn(new ModelConfigService.RuntimeModelBundle(
                        List.of(Map.of("provider_code", "demo")),
                        List.of(Map.of("model_code", "default-chat", "provider_code", "demo"))
                ));
        when(pythonClient.classifyIntent(anyMap())).thenReturn(Mono.just(Map.of(
                "matched", true,
                "intent_code", "book_hotel",
                "target_type", "workflow",
                "target_code", "hotel_booking",
                "workflow_code", "hotel_booking",
                "confidence", 0.91d,
                "reason", "default model routed"
        )));

        RoutingDecision decision = workflowService.routeMessage("定酒店", null);

        assertThat(decision.decision()).isEqualTo("start");
        assertThat(decision.workflowCode()).isEqualTo("hotel_booking");

        ArgumentCaptor<Map<String, Object>> requestCaptor = ArgumentCaptor.forClass(Map.class);
        verify(pythonClient).classifyIntent(requestCaptor.capture());
        assertThat(requestCaptor.getValue()).containsEntry("routing_model_code", "default-chat");
        assertThat((List<?>) requestCaptor.getValue().get("provider_configs")).hasSize(1);
        assertThat((List<?>) requestCaptor.getValue().get("model_records")).hasSize(1);
    }

    @Test
    void routeMessage_acceptsWorkflowNamePhraseBeforeModelFallback() throws Exception {
        WorkflowVersion flightVersion = publishedVersion(
                "flight_booking",
                "1.0.0",
                Map.of("routing_model_code", "route-model"),
                List.of(),
                List.of()
        );
        Workflow flightWorkflow = publishedWorkflow("flight_booking", "1.0.0", "机票预订", null);

        when(workflowRepository.findByStatusOrderByCreatedAtDesc(WorkflowStatus.PUBLISHED))
                .thenReturn(List.of(flightWorkflow));
        when(workflowVersionRepository.findByWorkflowCodeAndVersion("flight_booking", "1.0.0"))
                .thenReturn(Optional.of(flightVersion));
        when(workflowRepository.findByWorkflowCode("flight_booking"))
                .thenReturn(Optional.of(flightWorkflow));
        when(modelConfigService.resolveRoutingModelCode(anyCollection())).thenReturn("route-model");
        when(modelConfigService.buildRuntimeBundle(anyCollection(), eq("route-model")))
                .thenReturn(new ModelConfigService.RuntimeModelBundle(List.of(), List.of()));

        RoutingDecision decision = workflowService.routeMessage("我要机票预订", null);

        assertThat(decision.decision()).isEqualTo("start");
        assertThat(decision.workflowCode()).isEqualTo("flight_booking");
        assertThat(decision.workflowVersion()).isEqualTo("1.0.0");
        assertThat(decision.reason()).isEqualTo("phrase_match");
    }

    @Test
    void routeMessage_matchesConfiguredIntentPhrases() throws Exception {
        WorkflowVersion hotelVersion = publishedVersion(
                "hotel_booking",
                "1.0.0",
                Map.of("routing_model_code", "route-model"),
                List.of("预订酒店", "预定酒店", "订酒店", "定酒店"),
                List.of()
        );
        WorkflowVersion flightVersion = publishedVersion(
                "flight_booking",
                "1.0.0",
                Map.of("routing_model_code", "route-model"),
                List.of("预订机票", "预定机票", "订机票", "定机票", "预订航班", "预定航班", "订航班", "定航班"),
                List.of()
        );
        WorkflowVersion meetingRoomVersion = publishedVersion(
                "meeting_room_booking",
                "1.0.0",
                Map.of("routing_model_code", "route-model"),
                List.of("预订会议室", "预定会议室", "订会议室", "定会议室"),
                List.of()
        );
        Workflow hotelWorkflow = publishedWorkflow("hotel_booking", "1.0.0", "预订酒店", "帮助用户预订酒店");
        Workflow flightWorkflow = publishedWorkflow("flight_booking", "1.0.0", "预定航班", "帮助用户预订航班和机票");
        Workflow meetingRoomWorkflow = publishedWorkflow("meeting_room_booking", "1.0.0", "预订会议室", "帮助用户预订会议室");

        when(workflowRepository.findByStatusOrderByCreatedAtDesc(WorkflowStatus.PUBLISHED))
                .thenReturn(List.of(hotelWorkflow, flightWorkflow, meetingRoomWorkflow));
        when(workflowVersionRepository.findByWorkflowCodeAndVersion("hotel_booking", "1.0.0"))
                .thenReturn(Optional.of(hotelVersion));
        when(workflowVersionRepository.findByWorkflowCodeAndVersion("flight_booking", "1.0.0"))
                .thenReturn(Optional.of(flightVersion));
        when(workflowVersionRepository.findByWorkflowCodeAndVersion("meeting_room_booking", "1.0.0"))
                .thenReturn(Optional.of(meetingRoomVersion));
        when(modelConfigService.resolveRoutingModelCode(anyCollection())).thenReturn("route-model");
        when(modelConfigService.buildRuntimeBundle(anyCollection(), eq("route-model")))
                .thenReturn(new ModelConfigService.RuntimeModelBundle(List.of(), List.of()));

        Map<String, String> expectedWorkflowCodes = Map.ofEntries(
                Map.entry("我要预订酒店", "hotel_booking"),
                Map.entry("我要预定酒店", "hotel_booking"),
                Map.entry("我要订酒店", "hotel_booking"),
                Map.entry("我要定酒店", "hotel_booking"),
                Map.entry("我要预订机票", "flight_booking"),
                Map.entry("我要预定机票", "flight_booking"),
                Map.entry("我要订机票", "flight_booking"),
                Map.entry("我要定机票", "flight_booking"),
                Map.entry("我要预订航班", "flight_booking"),
                Map.entry("我要预定航班", "flight_booking"),
                Map.entry("我要订航班", "flight_booking"),
                Map.entry("我要定航班", "flight_booking"),
                Map.entry("我要预订会议室", "meeting_room_booking"),
                Map.entry("我要预定会议室", "meeting_room_booking"),
                Map.entry("我要订会议室", "meeting_room_booking"),
                Map.entry("我要定会议室", "meeting_room_booking")
        );

        expectedWorkflowCodes.forEach((message, expectedWorkflowCode) -> {
            RoutingDecision decision = workflowService.routeMessage(message, null);

            assertThat(decision.decision()).as(message).isEqualTo("start");
            assertThat(decision.workflowCode()).as(message).isEqualTo(expectedWorkflowCode);
            assertThat(decision.reason()).as(message).isIn("regex_match", "phrase_match");
        });
    }

    @Test
    void saveWorkflowDraft_appliesBackendDefaultModelBindingsWhenFrontendOmitsThem() throws Exception {
        Workflow workflow = publishedWorkflow("flight_booking", null, "机票预订", "预订航班和机票");
        workflow.setWorkspaceId(1L);

        CreateWorkflowVersionRequest request = new CreateWorkflowVersionRequest();
        request.setWorkflowName("机票预订");
        request.setWorkflowDescription("预订航班和机票");
        request.setVersion("draft");
        request.setDefinition(objectMapper.writeValueAsString(Map.of(
                "schema_version", "workflow-designer/v2",
                "main_graph_id", "main",
                "graphs", Map.of(
                        "main", Map.of(
                                "graph_id", "main",
                                "graph_type", "main",
                                "graph_name", "机票预订",
                                "graph_description", "预订航班和机票",
                                "entry_node_id", "start",
                                "nodes", Map.of(
                                        "start", Map.of("id", "start", "type", "start", "config", Map.of())
                                ),
                                "edges", List.of()
                        )
                )
        )));
        request.setConfig(objectMapper.writeValueAsString(Map.of(
                "schema_version", "workflow-designer/v2",
                "main_graph_id", "main",
                "variable_registry", Map.of("global", List.of(), "temporary", List.of())
        )));

        when(workflowRepository.findByWorkflowCode("flight_booking")).thenReturn(Optional.of(workflow));
        when(workflowVersionRepository.findByWorkflowCodeAndVersion("flight_booking", "draft"))
                .thenReturn(Optional.empty());
        when(workflowVersionRepository.save(any(WorkflowVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(modelConfigService.resolveConfiguredPurposeModelCode("routing")).thenReturn("doubao-chat");
        when(modelConfigService.resolveConfiguredPurposeModelCode("default")).thenReturn("doubao-chat");
        when(modelConfigService.isModelCodeAvailable("doubao-chat")).thenReturn(true);

        workflowService.saveWorkflowDraft("demo-admin", "flight_booking", request);

        ArgumentCaptor<WorkflowVersion> versionCaptor = ArgumentCaptor.forClass(WorkflowVersion.class);
        verify(workflowVersionRepository).save(versionCaptor.capture());
        WorkflowVersion saved = versionCaptor.getValue();

        JsonNode definition = objectMapper.readTree(saved.getDefinition());
        assertThat(definition.at("/model_bindings/routing_model_code").asText()).isEqualTo("doubao-chat");
        assertThat(definition.at("/model_bindings/llm_defaults/model_code").asText()).isEqualTo("doubao-chat");

        JsonNode config = objectMapper.readTree(saved.getConfig());
        assertThat(config.at("/model_bindings/routing_model_code").asText()).isEqualTo("doubao-chat");
        assertThat(config.at("/model_bindings/llm_defaults/model_code").asText()).isEqualTo("doubao-chat");
        assertThat(config.at("/llm_defaults/model_code").asText()).isEqualTo("doubao-chat");

        JsonNode snapshot = objectMapper.readTree(saved.getWorkflowSnapshot());
        assertThat(snapshot.at("/designer/definition/model_bindings/routing_model_code").asText()).isEqualTo("doubao-chat");
        assertThat(snapshot.at("/designer/workflow_config/model_bindings/routing_model_code").asText()).isEqualTo("doubao-chat");
    }

    @Test
    void publishWorkflow_rejectsSubAgentWithoutSubgraphBinding() throws Exception {
        Workflow workflow = publishedWorkflow("flight_booking", null, "机票预订", "预订航班和机票");
        workflow.setWorkspaceId(1L);
        WorkflowVersion version = new WorkflowVersion();
        version.setWorkflowCode("flight_booking");
        version.setVersion("v1");
        version.setStatus(WorkflowVersionStatus.DRAFT);
        version.setDefinition(objectMapper.writeValueAsString(Map.of(
                "schema_version", "workflow-designer/v2",
                "main_graph_id", "main",
                "graphs", Map.of(
                        "main", Map.of(
                                "graph_id", "main",
                                "graph_type", "main",
                                "graph_name", "机票预订",
                                "graph_description", "预订航班和机票",
                                "entry_node_id", "coordinator_main",
                                "nodes", Map.of(
                                        "coordinator_main", Map.of(
                                                "id", "coordinator_main",
                                                "type", "coordinator",
                                                "config", Map.of("prompt", "选择子流程")
                                        ),
                                        "book_flight", Map.of(
                                                "id", "book_flight",
                                                "type", "sub_agent",
                                                "config", Map.of("prompt", "预定机票")
                                        )
                                ),
                                "edges", List.of(Map.of(
                                        "id", "e1",
                                        "source", "coordinator_main",
                                        "target", "book_flight"
                                ))
                        )
                )
        )));
        version.setConfig("{}");

        when(workflowRepository.findByWorkflowCode("flight_booking")).thenReturn(Optional.of(workflow));
        when(workflowVersionRepository.findByWorkflowCodeAndVersion("flight_booking", "v1"))
                .thenReturn(Optional.of(version));

        assertThatThrownBy(() -> workflowService.publishWorkflow("demo-admin", "flight_booking", "v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工作流发布校验失败")
                .hasMessageContaining("subgraph_id");
        verify(workflowVersionRepository, never()).save(any(WorkflowVersion.class));
        verify(workflowRepository, never()).save(any(Workflow.class));
    }

    private WorkflowVersion publishedVersion(String workflowCode, String version, Map<String, Object> config) throws Exception {
        return publishedVersion(workflowCode, version, config, List.of("酒店"), List.of(workflowCode));
    }

    private WorkflowVersion publishedVersion(
            String workflowCode,
            String version,
            Map<String, Object> config,
            List<String> keywords,
            List<String> intentCodes
    ) throws Exception {
        WorkflowVersion workflowVersion = new WorkflowVersion();
        workflowVersion.setWorkflowCode(workflowCode);
        workflowVersion.setVersion(version);
        workflowVersion.setStatus(WorkflowVersionStatus.PUBLISHED);
        workflowVersion.setDefinition(objectMapper.writeValueAsString(Map.of(
                "schema_version", "workflow-designer/v2",
                "main_graph_id", "main",
                "graphs", Map.of(
                        "main", Map.of(
                                "graph_id", "main",
                                "graph_type", "main",
                                "entry_node_id", "start",
                                "nodes", Map.of(
                                        "start", Map.of("id", "start", "type", "start", "config", Map.of()),
                                        "extract_slots", Map.of("id", "extract_slots", "type", "llm", "config", Map.of("prompt", "hotel_slot_extraction"))
                                ),
                                "edges", List.of(Map.of("id", "e1", "source", "start", "target", "extract_slots"))
                        )
                )
        )));
        workflowVersion.setEntryRule(objectMapper.writeValueAsString(Map.of(
                "keywords", keywords,
                "intent_codes", intentCodes,
                "priority", 100
        )));
        workflowVersion.setConfig(objectMapper.writeValueAsString(config));
        return workflowVersion;
    }

    private Workflow publishedWorkflow(String workflowCode, String currentVersion, String name, String description) {
        Workflow workflow = new Workflow();
        workflow.setWorkflowCode(workflowCode);
        workflow.setCurrentVersion(currentVersion);
        workflow.setName(name);
        workflow.setDescription(description);
        workflow.setStatus(WorkflowStatus.PUBLISHED);
        return workflow;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
