package robot.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import robot.agent.config.WorkflowRoutingProperties;
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
    private WorkflowRoutingProperties workflowRoutingProperties;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        workflowRoutingProperties = new WorkflowRoutingProperties();
        workflowService = new WorkflowService(
                workflowRepository,
                workflowVersionRepository,
                objectMapper,
                accessControlService,
                auditService,
                pythonClient,
                modelConfigService,
                workflowRoutingProperties
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
    void workflowRoutingProperties_defaultsAndClampsThresholds() {
        WorkflowRoutingProperties properties = new WorkflowRoutingProperties();

        assertThat(properties.getRegexAcceptThreshold()).isEqualTo(1.0d);
        assertThat(properties.getPhraseAcceptThreshold()).isEqualTo(1.0d);
        assertThat(properties.getRagAcceptThreshold()).isEqualTo(0.80d);
        assertThat(properties.getSingleRagAcceptThreshold()).isEqualTo(0.60d);
        assertThat(properties.getLlmAcceptThreshold()).isEqualTo(0.70d);

        properties.setRagAcceptThreshold(2.0d);
        properties.setLlmAcceptThreshold(-1.0d);

        assertThat(properties.getRagAcceptThreshold()).isEqualTo(1.0d);
        assertThat(properties.getLlmAcceptThreshold()).isEqualTo(0.0d);
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

        RoutingDecision decision = workflowService.routeMessage("住宿安排", null);

        assertThat(decision.decision()).isEqualTo("start");
        assertThat(decision.workflowCode()).isEqualTo("hotel_booking");

        ArgumentCaptor<Map<String, Object>> requestCaptor = ArgumentCaptor.forClass(Map.class);
        verify(pythonClient).classifyIntent(requestCaptor.capture());
        assertThat(requestCaptor.getValue()).containsEntry("routing_model_code", "default-chat");
        assertThat((List<?>) requestCaptor.getValue().get("provider_configs")).hasSize(1);
        assertThat((List<?>) requestCaptor.getValue().get("model_records")).hasSize(1);
    }

    @Test
    void routeMessage_sendsOnlyRoutingModelBundleToIntentClassifier() throws Exception {
        WorkflowVersion travelVersion = publishedVersion(
                "travel_booking",
                "1.0.0",
                Map.of("routing_model_code", "route-model"),
                List.of("travel booking"),
                List.of("book_travel")
        );
        Workflow travelWorkflow = publishedWorkflow("travel_booking", "1.0.0", "Travel Booking", "Book trips");

        when(workflowRepository.findByStatusOrderByCreatedAtDesc(WorkflowStatus.PUBLISHED))
                .thenReturn(List.of(travelWorkflow));
        when(workflowVersionRepository.findByWorkflowCodeAndVersion("travel_booking", "1.0.0"))
                .thenReturn(Optional.of(travelVersion));
        when(workflowRepository.findByWorkflowCode("travel_booking"))
                .thenReturn(Optional.of(travelWorkflow));
        when(modelConfigService.resolveRoutingModelCode(anyCollection())).thenReturn("route-model");
        when(modelConfigService.buildRuntimeBundle(anyCollection(), eq("route-model")))
                .thenReturn(new ModelConfigService.RuntimeModelBundle(
                        List.of(
                                Map.of("provider_code", "route-provider"),
                                Map.of("provider_code", "workflow-provider")
                        ),
                        List.of(
                                Map.of("model_code", "route-model", "provider_code", "route-provider"),
                                Map.of("model_code", "workflow-model", "provider_code", "workflow-provider")
                        )
                ));
        when(pythonClient.classifyIntent(anyMap())).thenReturn(Mono.just(Map.of(
                "matched", true,
                "intent_code", "book_travel",
                "target_type", "workflow",
                "target_code", "travel_booking",
                "workflow_code", "travel_booking",
                "confidence", 0.91d,
                "reason", "route model selected travel"
        )));

        RoutingDecision decision = workflowService.routeMessage("please arrange travel", null);

        assertThat(decision.decision()).isEqualTo("start");
        assertThat(decision.workflowCode()).isEqualTo("travel_booking");
        ArgumentCaptor<Map<String, Object>> requestCaptor = ArgumentCaptor.forClass(Map.class);
        verify(pythonClient).classifyIntent(requestCaptor.capture());
        List<?> providerConfigs = (List<?>) requestCaptor.getValue().get("provider_configs");
        List<?> modelRecords = (List<?>) requestCaptor.getValue().get("model_records");
        assertThat(providerConfigs).hasSize(1);
        assertThat(((Map<?, ?>) providerConfigs.get(0)).get("provider_code")).isEqualTo("route-provider");
        assertThat(modelRecords).hasSize(1);
        assertThat(((Map<?, ?>) modelRecords.get(0)).get("model_code")).isEqualTo("route-model");
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
    void routeMessage_acceptsSingleRagCandidateBeforeModelFallback() throws Exception {
        WorkflowVersion cargoVersion = publishedVersion(
                "cargo_query",
                "1.0.0",
                Map.of("routing_model_code", "route-model"),
                List.of(),
                List.of()
        );
        Workflow cargoWorkflow = publishedWorkflow("cargo_query", "1.0.0", "Cargo Query", null);
        cargoVersion.setEntryRule(objectMapper.writeValueAsString(Map.of(
                "examples", List.of("我要", "查询", "货物"),
                "keywords", List.of(),
                "intent_codes", List.of(),
                "priority", 100
        )));

        when(workflowRepository.findByStatusOrderByCreatedAtDesc(WorkflowStatus.PUBLISHED))
                .thenReturn(List.of(cargoWorkflow));
        when(workflowVersionRepository.findByWorkflowCodeAndVersion("cargo_query", "1.0.0"))
                .thenReturn(Optional.of(cargoVersion));
        when(workflowRepository.findByWorkflowCode("cargo_query"))
                .thenReturn(Optional.of(cargoWorkflow));
        when(modelConfigService.resolveRoutingModelCode(anyCollection())).thenReturn("route-model");
        when(modelConfigService.buildRuntimeBundle(anyCollection(), eq("route-model")))
                .thenReturn(new ModelConfigService.RuntimeModelBundle(
                        List.of(Map.of("provider_code", "demo")),
                        List.of(Map.of("model_code", "route-model", "provider_code", "demo"))
                ));

        RoutingDecision decision = workflowService.routeMessage("我要查询货物", null);

        assertThat(decision.decision()).isEqualTo("start");
        assertThat(decision.workflowCode()).isEqualTo("cargo_query");
        assertThat(decision.reason()).isEqualTo("rag_single_candidate_match");
        verify(pythonClient, never()).classifyIntent(anyMap());
    }

    @Test
    void routeMessage_usesConfiguredSingleRagThreshold() throws Exception {
        workflowRoutingProperties.setSingleRagAcceptThreshold(0.75d);
        WorkflowVersion cargoVersion = publishedVersion(
                "cargo_query",
                "1.0.0",
                Map.of("routing_model_code", "route-model"),
                List.of(),
                List.of()
        );
        Workflow cargoWorkflow = publishedWorkflow("cargo_query", "1.0.0", "Cargo Query", null);
        cargoVersion.setEntryRule(objectMapper.writeValueAsString(Map.of(
                "examples", List.of("我要", "查询", "货物"),
                "keywords", List.of(),
                "intent_codes", List.of(),
                "priority", 100
        )));

        when(workflowRepository.findByStatusOrderByCreatedAtDesc(WorkflowStatus.PUBLISHED))
                .thenReturn(List.of(cargoWorkflow));
        when(workflowVersionRepository.findByWorkflowCodeAndVersion("cargo_query", "1.0.0"))
                .thenReturn(Optional.of(cargoVersion));
        when(workflowRepository.findByWorkflowCode("cargo_query"))
                .thenReturn(Optional.of(cargoWorkflow));
        when(modelConfigService.resolveRoutingModelCode(anyCollection())).thenReturn("route-model");
        when(modelConfigService.buildRuntimeBundle(anyCollection(), eq("route-model")))
                .thenReturn(new ModelConfigService.RuntimeModelBundle(
                        List.of(Map.of("provider_code", "demo")),
                        List.of(Map.of("model_code", "route-model", "provider_code", "demo"))
                ));
        when(pythonClient.classifyIntent(anyMap())).thenReturn(Mono.just(Map.of(
                "matched", true,
                "intent_code", "cargo_query",
                "target_type", "workflow",
                "target_code", "cargo_query",
                "workflow_code", "cargo_query",
                "confidence", 0.91d,
                "reason", "model fallback after configured threshold"
        )));

        RoutingDecision decision = workflowService.routeMessage("我要查询货物", null);

        assertThat(decision.decision()).isEqualTo("start");
        assertThat(decision.workflowCode()).isEqualTo("cargo_query");
        assertThat(decision.reason()).isEqualTo("llm_match");
        verify(pythonClient).classifyIntent(anyMap());
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
    void saveWorkflowDraft_doesNotInjectBackendDefaultModelBindingsWhenFrontendOmitsThem() throws Exception {
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

        workflowService.saveWorkflowDraft("demo-admin", "flight_booking", request);

        ArgumentCaptor<WorkflowVersion> versionCaptor = ArgumentCaptor.forClass(WorkflowVersion.class);
        verify(workflowVersionRepository).save(versionCaptor.capture());
        WorkflowVersion saved = versionCaptor.getValue();

        JsonNode definition = objectMapper.readTree(saved.getDefinition());
        assertThat(definition.at("/model_bindings").isObject()).isTrue();
        assertThat(definition.at("/model_bindings").isEmpty()).isTrue();

        JsonNode config = objectMapper.readTree(saved.getConfig());
        assertThat(config.has("model_bindings")).isFalse();
        assertThat(config.has("llm_defaults")).isFalse();

        JsonNode snapshot = objectMapper.readTree(saved.getWorkflowSnapshot());
        assertThat(snapshot.at("/designer/definition/model_bindings").isObject()).isTrue();
        assertThat(snapshot.at("/designer/definition/model_bindings").isEmpty()).isTrue();
        assertThat(snapshot.at("/designer/workflow_config").has("model_bindings")).isFalse();
        verify(modelConfigService, never()).resolveConfiguredPurposeModelCode(any());
        verify(modelConfigService, never()).isModelCodeAvailable(any());
    }

    @Test
    void saveWorkflowDraft_persistsWithoutCheckingModelAvailability() throws Exception {
        Workflow workflow = publishedWorkflow("flight_booking", null, "Flight Booking", "Book flights");
        workflow.setWorkspaceId(1L);

        CreateWorkflowVersionRequest request = new CreateWorkflowVersionRequest();
        request.setWorkflowName("Flight Booking");
        request.setWorkflowDescription("Book flights");
        request.setVersion("draft");
        request.setDefinition(objectMapper.writeValueAsString(Map.of(
                "schema_version", "workflow-designer/v2",
                "main_graph_id", "main",
                "graphs", Map.of(
                        "main", Map.of(
                                "graph_id", "main",
                                "graph_type", "main",
                                "graph_name", "Flight Booking",
                                "graph_description", "Book flights",
                                "entry_node_id", "start",
                                "nodes", Map.of(
                                        "start", Map.of("id", "start", "type", "start", "config", Map.of())
                                ),
                                "edges", List.of()
                        )
                )
        )));
        request.setConfig("{}");

        when(workflowRepository.findByWorkflowCode("flight_booking")).thenReturn(Optional.of(workflow));
        when(workflowVersionRepository.findByWorkflowCodeAndVersion("flight_booking", "draft"))
                .thenReturn(Optional.empty());
        when(workflowVersionRepository.save(any(WorkflowVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));

        workflowService.saveWorkflowDraft("demo-admin", "flight_booking", request);

        ArgumentCaptor<WorkflowVersion> versionCaptor = ArgumentCaptor.forClass(WorkflowVersion.class);
        verify(workflowVersionRepository).save(versionCaptor.capture());
        WorkflowVersion saved = versionCaptor.getValue();

        JsonNode definition = objectMapper.readTree(saved.getDefinition());
        assertThat(definition.has("model_bindings")).isTrue();
        assertThat(definition.at("/model_bindings").isEmpty()).isTrue();

        JsonNode config = objectMapper.readTree(saved.getConfig());
        assertThat(config.has("model_bindings")).isFalse();

        JsonNode snapshot = objectMapper.readTree(saved.getWorkflowSnapshot());
        assertThat(snapshot.at("/designer/definition/model_bindings").isEmpty()).isTrue();
        assertThat(snapshot.at("/designer/workflow_config").isEmpty()).isTrue();
        verify(modelConfigService, never()).resolveConfiguredPurposeModelCode(any());
        verify(modelConfigService, never()).isModelCodeAvailable(any());
    }

    @Test
    void validateWorkflowDefinition_doesNotRequirePromptForStartAndEndNodes() throws Exception {
        Map<String, Object> definition = Map.of(
                "schema_version", "workflow-designer/v2",
                "main_graph_id", "main",
                "graphs", Map.of(
                        "main", Map.of(
                                "graph_id", "main",
                                "graph_type", "MAIN",
                                "graph_name", "商品推荐",
                                "graph_description", "根据用户输入推荐商品",
                                "entry_node_id", "coordinator_main",
                                "nodes", Map.of(
                                        "coordinator_main", Map.of(
                                                "id", "coordinator_main",
                                                "type", "coordinator",
                                                "config", Map.of("prompt", "选择商品推荐子流程")
                                        ),
                                        "product_sub_agent", Map.of(
                                                "id", "product_sub_agent",
                                                "type", "sub_agent",
                                                "config", Map.of(
                                                        "prompt", "处理商品推荐",
                                                        "subgraph_id", "product_graph"
                                                )
                                        )
                                ),
                                "edges", List.of(Map.of(
                                        "id", "e_main_product",
                                        "source", "coordinator_main",
                                        "target", "product_sub_agent"
                                ))
                        ),
                        "product_graph", Map.of(
                                "graph_id", "product_graph",
                                "graph_type", "SUBGRAPH",
                                "graph_name", "商品推荐子流程",
                                "graph_description", "提取商品并输出推荐结果",
                                "entry_node_id", "start",
                                "nodes", Map.of(
                                        "start", Map.of(
                                                "id", "start",
                                                "type", "start",
                                                "config", Map.of(
                                                        "input_variables", List.of(Map.of(
                                                                "name", "product_list",
                                                                "type", "String",
                                                                "description", "用户想查询的商品"
                                                        ))
                                                )
                                        ),
                                        "end", Map.of(
                                                "id", "end",
                                                "type", "end",
                                                "config", Map.of("output_format", Map.of(
                                                        "product_list", "$session.product_list"
                                                ))
                                        )
                                ),
                                "edges", List.of(Map.of(
                                        "id", "e_start_end",
                                        "source", "start",
                                        "target", "end"
                                ))
                        )
                )
        );

        List<Map<String, Object>> issues = workflowService.validateWorkflowDefinition(
                objectMapper.writeValueAsString(definition),
                "{}"
        );

        assertThat(issues)
                .filteredOn(issue -> "config.prompt".equals(issue.get("field")))
                .isEmpty();
        assertThat(issues).isEmpty();
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

    @Test
    void publishWorkflow_acceptsApiExecutionNodeInSubgraph() throws Exception {
        Workflow workflow = publishedWorkflow("flight_booking", null, "机票预订", "预订航班和机票");
        workflow.setWorkspaceId(1L);
        WorkflowVersion version = new WorkflowVersion();
        version.setWorkflowCode("flight_booking");
        version.setVersion("v1");
        version.setStatus(WorkflowVersionStatus.DRAFT);
        version.setDefinition(objectMapper.writeValueAsString(Map.of(
                "schema_version", "workflow-designer/v2",
                "main_graph_id", "main",
                "variables", Map.of("global", List.of(Map.of(
                        "id", "origin",
                        "name", "origin",
                        "type", "String",
                        "scope", "global"
                )), "temporary", List.of()),
                "graphs", Map.of(
                        "main", Map.of(
                                "graph_id", "main",
                                "graph_type", "MAIN",
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
                                                "config", Map.of(
                                                        "prompt", "预定机票",
                                                        "subgraph_id", "book_flight_graph"
                                                )
                                        )
                                ),
                                "edges", List.of(Map.of(
                                        "id", "e1",
                                        "source", "coordinator_main",
                                        "target", "book_flight"
                                ))
                        ),
                        "book_flight_graph", Map.of(
                                "graph_id", "book_flight_graph",
                                "graph_type", "SUBGRAPH",
                                "graph_name", "API 子流程",
                                "graph_description", "调用能力中心 API",
                                "entry_node_id", "start",
                                "nodes", Map.of(
                                        "start", Map.of(
                                                "id", "start",
                                                "type", "start",
                                                "config", Map.of("prompt", "开始")
                                        ),
                                        "flight_price", Map.of(
                                                "id", "flight_price",
                                                "type", "api",
                                                "config", Map.of(
                                                        "invoke_type", "capability",
                                                        "group_id", 91,
                                                        "capability_code", "flight_price_api",
                                                        "capability_version", "v202605300001",
                                                        "payload_mapping", Map.of("origin", "$global.origin")
                                                )
                                        ),
                                        "end", Map.of(
                                                "id", "end",
                                                "type", "end",
                                                "config", Map.of("prompt", "结束", "output_format", Map.of())
                                        )
                                ),
                                "edges", List.of(
                                        Map.of("id", "s1", "source", "start", "target", "flight_price"),
                                        Map.of("id", "s2", "source", "flight_price", "target", "end")
                                )
                        )
                )
        )));
        version.setConfig("{}");

        when(workflowRepository.findByWorkflowCode("flight_booking")).thenReturn(Optional.of(workflow));
        when(workflowVersionRepository.findByWorkflowCodeAndVersion("flight_booking", "v1"))
                .thenReturn(Optional.of(version));
        when(workflowVersionRepository.save(any(WorkflowVersion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(invocation -> invocation.getArgument(0));

        workflowService.publishWorkflow("demo-admin", "flight_booking", "v1");

        ArgumentCaptor<WorkflowVersion> versionCaptor = ArgumentCaptor.forClass(WorkflowVersion.class);
        verify(workflowVersionRepository).save(versionCaptor.capture());
        JsonNode definition = objectMapper.readTree(versionCaptor.getValue().getDefinition());
        assertThat(definition.at("/graphs/book_flight_graph/nodes/flight_price/type").asText()).isEqualTo("api");
        assertThat(versionCaptor.getValue().getStatus()).isEqualTo(WorkflowVersionStatus.PUBLISHED);
        verify(workflowRepository).save(any(Workflow.class));
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
