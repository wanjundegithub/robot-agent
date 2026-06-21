package robot.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import robot.agent.config.KnowledgeProperties;
import robot.agent.config.WorkflowPromptProperties;
import robot.agent.config.WorkflowRoutingProperties;
import robot.agent.dto.request.KnowledgeSearchRequest;
import robot.agent.dto.response.KnowledgeSearchResponse;
import robot.agent.model.Execution;
import robot.agent.model.ExecutionStatus;
import robot.agent.model.KnowledgeBinding;
import robot.agent.model.KnowledgeBindingScope;
import robot.agent.model.Workflow;
import robot.agent.model.WorkflowStatus;
import robot.agent.model.WorkflowVersion;
import robot.agent.model.WorkflowVersionStatus;
import robot.agent.repository.WorkflowRepository;
import robot.agent.repository.WorkflowVersionRepository;
import robot.agent.service.knowledge.KnowledgeBindingService;
import robot.agent.service.knowledge.KnowledgeRouteDecisionService;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowKnowledgeRouteServiceTest {

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

    @Mock
    private KnowledgeBindingService knowledgeBindingService;

    @Mock
    private KnowledgeService knowledgeService;

    private WorkflowService workflowService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        WorkflowRoutingProperties routingProperties = new WorkflowRoutingProperties();
        routingProperties.setRegexAcceptThreshold(1.0d);
        routingProperties.setPhraseAcceptThreshold(1.0d);
        routingProperties.setRagAcceptThreshold(1.0d);
        routingProperties.setSingleRagAcceptThreshold(1.0d);
        routingProperties.setLlmAcceptThreshold(0.75d);
        workflowService = new WorkflowService(
                workflowRepository,
                workflowVersionRepository,
                objectMapper,
                accessControlService,
                auditService,
                pythonClient,
                modelConfigService,
                new WorkflowPromptProperties(),
                routingProperties,
                knowledgeBindingService,
                new KnowledgeRouteDecisionService(new KnowledgeProperties()),
                knowledgeService
        );
    }

    @Test
    void routeMessageWithSessionDoesNotSearchKnowledgeWhenNoBindingExists() throws Exception {
        arrangeWorkflowAndLowIntent();
        when(knowledgeBindingService.getBindings(KnowledgeBindingScope.SESSION, "session_1")).thenReturn(List.of());

        RoutingDecision decision = workflowService.routeMessage("保修期多久", null, "session_1", "demo-user");

        assertThat(decision.decision()).isEqualTo("clarification_required");
        verify(knowledgeService, never()).searchKnowledge(any(), any());
    }

    @Test
    void routeMessageWithSessionReturnsKnowledgeAnswerWhenBoundKnowledgeScoresHigh() throws Exception {
        arrangeWorkflowAndLowIntent();
        when(knowledgeBindingService.getBindings(KnowledgeBindingScope.SESSION, "session_1"))
                .thenReturn(List.of(binding("kb_product")));
        KnowledgeSearchResponse searchResponse = new KnowledgeSearchResponse();
        searchResponse.setQuery("保修期多久");
        searchResponse.setAnswer("保修期为一年。");
        searchResponse.setBestScore(0.92d);
        when(knowledgeService.searchKnowledge(eq("demo-user"), any(KnowledgeSearchRequest.class))).thenReturn(searchResponse);

        RoutingDecision decision = workflowService.routeMessage("保修期多久", null, "session_1", "demo-user");

        assertThat(decision.decision()).isEqualTo("knowledge_answer");
        assertThat(decision.reason()).isEqualTo("knowledge_primary");
        assertThat(decision.confidence()).isEqualTo(0.92d);
        assertThat(decision.clarificationQuestion()).isEqualTo("保修期为一年。");
        ArgumentCaptor<KnowledgeSearchRequest> requestCaptor = ArgumentCaptor.forClass(KnowledgeSearchRequest.class);
        verify(knowledgeService).searchKnowledge(eq("demo-user"), requestCaptor.capture());
        assertThat(requestCaptor.getValue().getKbCodes()).containsExactly("kb_product");
        assertThat(requestCaptor.getValue().getGenerateAnswer()).isFalse();
    }

    @Test
    void routeMessageWithActiveWorkflowSearchesWorkflowBindings() throws Exception {
        arrangeWorkflowAndLowIntent();
        Execution activeExecution = new Execution();
        activeExecution.setId("execution_1");
        activeExecution.setWorkflowCode("flight_booking");
        activeExecution.setStatus(ExecutionStatus.RUNNING);
        when(knowledgeBindingService.getBindings(KnowledgeBindingScope.SESSION, "session_1")).thenReturn(List.of());
        when(knowledgeBindingService.getBindings(KnowledgeBindingScope.WORKFLOW, "flight_booking"))
                .thenReturn(List.of(binding(KnowledgeBindingScope.WORKFLOW, "flight_booking", "kb_workflow")));
        KnowledgeSearchResponse searchResponse = new KnowledgeSearchResponse();
        searchResponse.setQuery("保修期多久");
        searchResponse.setAnswer("工作流知识：保修期为一年。");
        searchResponse.setBestScore(0.91d);
        when(knowledgeService.searchKnowledge(eq("demo-user"), any(KnowledgeSearchRequest.class))).thenReturn(searchResponse);

        RoutingDecision decision = workflowService.routeMessage("保修期多久", activeExecution, "session_1", "demo-user");

        assertThat(decision.decision()).isEqualTo("knowledge_answer");
        assertThat(decision.clarificationQuestion()).isEqualTo("工作流知识：保修期为一年。");
        ArgumentCaptor<KnowledgeSearchRequest> requestCaptor = ArgumentCaptor.forClass(KnowledgeSearchRequest.class);
        verify(knowledgeService).searchKnowledge(eq("demo-user"), requestCaptor.capture());
        assertThat(requestCaptor.getValue().getKbCodes()).containsExactly("kb_workflow");
    }

    @Test
    void routeMessageWithHighIntentDoesNotSearchBoundKnowledge() throws Exception {
        arrangeWorkflowAndHighIntent();
        lenient().when(knowledgeBindingService.getBindings(KnowledgeBindingScope.SESSION, "session_1"))
                .thenReturn(List.of(binding("kb_product")));

        RoutingDecision decision = workflowService.routeMessage("我想办理业务", null, "session_1", "demo-user");

        assertThat(decision.decision()).isEqualTo("start");
        assertThat(decision.workflowCode()).isEqualTo("flight_booking");
        verify(knowledgeService, never()).searchKnowledge(any(), any());
    }

    private void arrangeWorkflowAndLowIntent() throws Exception {
        arrangeWorkflowAndIntent(false, 0.20d, "no workflow intent");
    }

    private void arrangeWorkflowAndHighIntent() throws Exception {
        arrangeWorkflowAndIntent(true, 0.90d, "workflow intent");
    }

    private void arrangeWorkflowAndIntent(boolean matched, double confidence, String reason) throws Exception {
        WorkflowVersion version = publishedVersion("flight_booking", "1.0.0");
        Workflow workflow = publishedWorkflow("flight_booking", "1.0.0");
        when(workflowRepository.findByStatusOrderByCreatedAtDesc(WorkflowStatus.PUBLISHED)).thenReturn(List.of(workflow));
        when(workflowVersionRepository.findByWorkflowCodeAndVersion("flight_booking", "1.0.0")).thenReturn(Optional.of(version));
        when(workflowRepository.findByWorkflowCode("flight_booking")).thenReturn(Optional.of(workflow));
        when(modelConfigService.resolveRoutingModelCode(anyCollection())).thenReturn("route-model");
        when(modelConfigService.buildRuntimeBundle(anyCollection(), eq("route-model")))
                .thenReturn(new ModelConfigService.RuntimeModelBundle(
                        List.of(Map.of("provider_code", "demo")),
                        List.of(Map.of("model_code", "route-model", "provider_code", "demo"))
                ));
        when(pythonClient.classifyIntent(anyMap())).thenReturn(Mono.just(Map.of(
                "matched", matched,
                "confidence", confidence,
                "reason", reason,
                "target_type", "workflow",
                "target_code", matched ? "flight_booking" : "",
                "workflow_code", matched ? "flight_booking" : "",
                "intent_code", matched ? "book_flight" : "",
                "need_clarification", !matched,
                "clarification_question", matched ? "" : "未匹配到流程"
        )));
    }

    private WorkflowVersion publishedVersion(String workflowCode, String version) throws Exception {
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
                                "nodes", Map.of("start", Map.of("id", "start", "type", "start", "config", Map.of())),
                                "edges", List.of()
                        )
                )
        )));
        workflowVersion.setEntryRule(objectMapper.writeValueAsString(Map.of(
                "keywords", List.of("机票"),
                "intent_codes", List.of("book_flight"),
                "priority", 100
        )));
        workflowVersion.setConfig(objectMapper.writeValueAsString(Map.of("routing_model_code", "route-model")));
        return workflowVersion;
    }

    private Workflow publishedWorkflow(String workflowCode, String currentVersion) {
        Workflow workflow = new Workflow();
        workflow.setWorkflowCode(workflowCode);
        workflow.setCurrentVersion(currentVersion);
        workflow.setName("机票预订");
        workflow.setStatus(WorkflowStatus.PUBLISHED);
        return workflow;
    }

    private KnowledgeBinding binding(String kbCode) {
        return binding(KnowledgeBindingScope.SESSION, "session_1", kbCode);
    }

    private KnowledgeBinding binding(KnowledgeBindingScope scope, String targetId, String kbCode) {
        KnowledgeBinding binding = new KnowledgeBinding();
        binding.setScope(scope);
        binding.setTargetId(targetId);
        binding.setWorkspaceId(1L);
        binding.setKbCode(kbCode);
        binding.setEnabled(true);
        binding.setBindingVersion(1);
        return binding;
    }
}
