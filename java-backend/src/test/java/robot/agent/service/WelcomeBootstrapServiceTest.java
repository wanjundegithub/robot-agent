package robot.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import robot.agent.dto.response.WorkflowVersionResponse;
import robot.agent.model.WorkflowVersion;
import robot.agent.model.WorkflowVersionStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WelcomeBootstrapServiceTest {

    @Mock
    private WorkflowService workflowService;

    @Mock
    private PythonClient pythonClient;

    @Mock
    private robot.agent.channel.core.UserConnectionManager userConnectionManager;

    @Mock
    private ModelConfigService modelConfigService;

    private WelcomeBootstrapService welcomeBootstrapService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        welcomeBootstrapService = new WelcomeBootstrapService(objectMapper, workflowService, modelConfigService, pythonClient, userConnectionManager);
    }

    @Test
    void bootstrap_skips_when_binding_is_incomplete() {
        welcomeBootstrapService.bootstrap("session-1", "hotel_booking", null);

        verifyNoInteractions(workflowService, pythonClient, userConnectionManager);
    }

    @Test
    void bootstrap_is_idempotent_for_same_session_workflow_version() throws Exception {
        stubPublishedWorkflow();
        when(pythonClient.decideWorkflowWelcome(anyMap())).thenReturn(Mono.just(Map.of(
                "should_greet", true,
                "message", "您好，我是酒店预订助手。",
                "reason", "first_open"
        )));

        welcomeBootstrapService.bootstrap("session-1", "hotel_booking", "1.0.0");
        welcomeBootstrapService.bootstrap("session-1", "hotel_booking", "1.0.0");

        verify(workflowService, times(1)).requirePublishedWorkflowVersion("hotel_booking", "1.0.0");
        verify(pythonClient, times(1)).decideWorkflowWelcome(anyMap());
        verify(userConnectionManager, times(1)).sendMessageDeltaFrame(
                org.mockito.ArgumentMatchers.startsWith("welcome_session-1_"),
                org.mockito.ArgumentMatchers.eq("session-1"),
                org.mockito.ArgumentMatchers.eq("您好，我是酒店预订助手。"),
                org.mockito.ArgumentMatchers.eq(true)
        );
    }

    @Test
    void bootstrap_skips_when_model_returns_no_greeting() throws Exception {
        stubPublishedWorkflow();
        when(pythonClient.decideWorkflowWelcome(anyMap())).thenReturn(Mono.just(Map.of(
                "should_greet", false,
                "message", "您好，我是酒店预订助手。",
                "reason", "no_greeting"
        )));

        welcomeBootstrapService.bootstrap("session-1", "hotel_booking", "1.0.0");

        verify(pythonClient).decideWorkflowWelcome(anyMap());
        verify(userConnectionManager, never()).sendMessageDeltaFrame(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean()
        );
    }

    @Test
    void bootstrap_publishes_welcome_message_when_model_accepts() throws Exception {
        stubPublishedWorkflow();
        when(pythonClient.decideWorkflowWelcome(anyMap())).thenReturn(Mono.just(Map.of(
                "should_greet", true,
                "message", "您好，我是酒店预订助手，可以帮您查询城市、日期和房型信息。",
                "reason", "welcome_is_appropriate"
        )));

        welcomeBootstrapService.bootstrap("session-1", "hotel_booking", "1.0.0");

        ArgumentCaptor<Map<String, Object>> requestCaptor = ArgumentCaptor.forClass(Map.class);
        verify(pythonClient).decideWorkflowWelcome(requestCaptor.capture());
        Map<String, Object> request = requestCaptor.getValue();
        assertThat(request).containsEntry("session_id", "session-1");
        assertThat(request).containsEntry("workflow_code", "hotel_booking");
        assertThat(request).containsEntry("workflow_version", "1.0.0");
        assertThat(request).containsEntry("routing_model_code", "general-chat-v1");
        assertThat(request).containsEntry("provider_configs", List.of(Map.of("provider_code", "openai")));
        assertThat(request).containsEntry("model_records", List.of(Map.of("model_code", "general-chat-v1")));
        assertThat(castMap(request.get("system_prompts")))
                .containsKeys("workflow_control", "intent_routing", "workflow_welcome");

        Map<String, Object> summary = castMap(request.get("workflow_summary"));
        assertThat(summary).containsEntry("name", "酒店预订助手");
        assertThat(summary).containsEntry("description", "帮助用户查询和预订酒店");
        assertThat(castMap(summary.get("entry_rule"))).containsEntry("intent_codes", List.of("general_agent_request"));
        assertThat(castListOfMaps(summary.get("coordinator_prompts"))).hasSize(1);
        assertThat(castListOfMaps(summary.get("opening_messages"))).hasSize(1);

        verify(userConnectionManager).sendMessageDeltaFrame(
                org.mockito.ArgumentMatchers.startsWith("welcome_session-1_"),
                org.mockito.ArgumentMatchers.eq("session-1"),
                org.mockito.ArgumentMatchers.eq("您好，我是酒店预订助手，可以帮您查询城市、日期和房型信息。"),
                org.mockito.ArgumentMatchers.eq(true)
        );
    }

    @Test
    void bootstrap_uses_default_model_when_workflow_has_no_model_binding() throws Exception {
        stubPublishedWorkflowWithRuntimeModelBundle(
                List.of(),
                List.of(),
                "intent-router"
        );
        when(modelConfigService.buildDefaultRuntimeBundle()).thenReturn(new ModelConfigService.RuntimeModelBundle(
                List.of(Map.of("provider_code", "default-provider")),
                List.of(Map.of("model_code", "default-chat"))
        ));
        when(pythonClient.decideWorkflowWelcome(anyMap())).thenReturn(Mono.just(Map.of(
                "should_greet", true,
                "message", "您好，我是机票预订助手。",
                "reason", "fallback_model"
        )));

        welcomeBootstrapService.bootstrap("session-1", "hotel_booking", "1.0.0");

        ArgumentCaptor<Map<String, Object>> requestCaptor = ArgumentCaptor.forClass(Map.class);
        verify(pythonClient).decideWorkflowWelcome(requestCaptor.capture());
        Map<String, Object> request = requestCaptor.getValue();
        assertThat(request.get("routing_model_code")).isEqualTo("default-chat");
        assertThat((List<?>) request.get("provider_configs")).hasSize(1);
        assertThat((List<?>) request.get("model_records")).hasSize(1);
        verify(userConnectionManager).sendMessageDeltaFrame(
                org.mockito.ArgumentMatchers.startsWith("welcome_session-1_"),
                org.mockito.ArgumentMatchers.eq("session-1"),
                org.mockito.ArgumentMatchers.eq("您好，我是机票预订助手。"),
                org.mockito.ArgumentMatchers.eq(true)
        );
    }

    @Test
    void bootstrap_publishes_opening_message_when_welcome_model_fails() throws Exception {
        stubPublishedWorkflow();
        when(pythonClient.decideWorkflowWelcome(anyMap())).thenReturn(Mono.error(new RuntimeException("provider timeout")));

        welcomeBootstrapService.bootstrap("session-1", "hotel_booking", "1.0.0");

        verify(userConnectionManager).sendMessageDeltaFrame(
                org.mockito.ArgumentMatchers.startsWith("welcome_session-1_"),
                org.mockito.ArgumentMatchers.eq("session-1"),
                org.mockito.ArgumentMatchers.eq("您好，我是酒店预订助手。"),
                org.mockito.ArgumentMatchers.eq(true)
        );
    }

    @Test
    void bootstrap_publishes_generic_welcome_when_model_fails_and_no_opening_message_exists() throws Exception {
        stubPublishedWorkflowWithRuntimeModelBundle(
                List.of(Map.of("provider_code", "openai")),
                List.of(Map.of("model_code", "general-chat-v1")),
                "general-chat-v1",
                sampleDefinitionWithoutOpeningMessage()
        );
        when(pythonClient.decideWorkflowWelcome(anyMap())).thenReturn(Mono.error(new RuntimeException("provider timeout")));

        welcomeBootstrapService.bootstrap("session-1", "hotel_booking", "1.0.0");

        verify(userConnectionManager).sendMessageDeltaFrame(
                org.mockito.ArgumentMatchers.startsWith("welcome_session-1_"),
                org.mockito.ArgumentMatchers.eq("session-1"),
                org.mockito.ArgumentMatchers.eq("您好，已进入「酒店预订助手」流程。请告诉我您需要什么服务。"),
                org.mockito.ArgumentMatchers.eq(true)
        );
    }

    private void stubPublishedWorkflow() throws Exception {
        stubPublishedWorkflowWithRuntimeModelBundle(
                List.of(Map.of("provider_code", "openai")),
                List.of(Map.of("model_code", "general-chat-v1")),
                "general-chat-v1"
        );
    }

    private void stubPublishedWorkflowWithRuntimeModelBundle(
            List<Map<String, Object>> providerConfigs,
            List<Map<String, Object>> modelRecords,
            String routingModelCode
    ) throws Exception {
        stubPublishedWorkflowWithRuntimeModelBundle(providerConfigs, modelRecords, routingModelCode, sampleDefinition());
    }

    private void stubPublishedWorkflowWithRuntimeModelBundle(
            List<Map<String, Object>> providerConfigs,
            List<Map<String, Object>> modelRecords,
            String routingModelCode,
            Map<String, Object> definition
    ) throws Exception {
        WorkflowVersion workflowVersion = new WorkflowVersion();
        workflowVersion.setWorkflowCode("hotel_booking");
        workflowVersion.setVersion("1.0.0");
        workflowVersion.setStatus(WorkflowVersionStatus.PUBLISHED);
        workflowVersion.setDefinition(objectMapper.writeValueAsString(definition));
        workflowVersion.setEntryRule(objectMapper.writeValueAsString(Map.of(
                "intent_codes", List.of("general_agent_request"),
                "keywords", List.of("酒店"),
                "regex_patterns", List.of(),
                "examples", List.of(),
                "priority", 100
        )));
        workflowVersion.setConfig(objectMapper.writeValueAsString(Map.of("model_bindings", Map.of())));

        WorkflowVersionResponse workflowVersionResponse = new WorkflowVersionResponse();
        workflowVersionResponse.setWorkflowCode("hotel_booking");
        workflowVersionResponse.setWorkflowName("酒店预订助手");
        workflowVersionResponse.setWorkflowDescription("帮助用户查询和预订酒店");
        workflowVersionResponse.setVersion("1.0.0");
        workflowVersionResponse.setStatus(WorkflowVersionStatus.PUBLISHED);
        workflowVersionResponse.setDefinition(workflowVersion.getDefinition());
        workflowVersionResponse.setEntryRule(workflowVersion.getEntryRule());
        workflowVersionResponse.setConfig(workflowVersion.getConfig());

        when(workflowService.requirePublishedWorkflowVersion("hotel_booking", "1.0.0")).thenReturn(workflowVersion);
        when(workflowService.getWorkflowVersion("hotel_booking", "1.0.0")).thenReturn(workflowVersionResponse);
        when(workflowService.buildRuntimeExecutionBundle("hotel_booking", "1.0.0"))
                .thenReturn(new WorkflowService.RuntimeExecutionBundle(
                        definition,
                        Map.of(
                                "intent_codes", List.of("general_agent_request"),
                                "keywords", List.of("酒店"),
                                "regex_patterns", List.of(),
                                "examples", List.of(),
                                "priority", 100
                        ),
                        Map.of("model_bindings", Map.of()),
                        Map.of(),
                        providerConfigs,
                        modelRecords,
                        routingModelCode
                ));
    }

    private Map<String, Object> sampleDefinitionWithoutOpeningMessage() {
        Map<String, Object> coordinator = new LinkedHashMap<>();
        coordinator.put("type", "coordinator");
        coordinator.put("description", "协调主流程");
        coordinator.put("config", Map.of(
                "prompt", "欢迎用户并说明能力",
                "user_prompt", "请先告诉我入住城市和日期"
        ));

        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("coordinator_1", coordinator);

        Map<String, Object> mainGraph = new LinkedHashMap<>();
        mainGraph.put("graph_type", "main");
        mainGraph.put("graph_description", "酒店预订主图");
        mainGraph.put("entry_node_id", "coordinator_1");
        mainGraph.put("nodes", nodes);
        mainGraph.put("edges", List.of());

        Map<String, Object> graphs = new LinkedHashMap<>();
        graphs.put("main", mainGraph);

        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("schema_version", "2");
        definition.put("main_graph_id", "main");
        definition.put("graphs", graphs);
        return definition;
    }

    private Map<String, Object> sampleDefinition() {
        Map<String, Object> coordinator = new LinkedHashMap<>();
        coordinator.put("type", "coordinator");
        coordinator.put("description", "协调主流程");
        coordinator.put("config", Map.of(
                "prompt", "欢迎用户并说明能力",
                "user_prompt", "请先告诉我入住城市和日期"
        ));

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "message");
        message.put("description", "固定欢迎话术");
        message.put("config", Map.of("message_text", "您好，我是酒店预订助手。"));

        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("coordinator_1", coordinator);
        nodes.put("message_1", message);

        Map<String, Object> mainGraph = new LinkedHashMap<>();
        mainGraph.put("graph_type", "main");
        mainGraph.put("graph_description", "酒店预订主图");
        mainGraph.put("entry_node_id", "coordinator_1");
        mainGraph.put("nodes", nodes);
        mainGraph.put("edges", List.of());

        Map<String, Object> graphs = new LinkedHashMap<>();
        graphs.put("main", mainGraph);

        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("schema_version", "2");
        definition.put("main_graph_id", "main");
        definition.put("graphs", graphs);
        return definition;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castListOfMaps(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
