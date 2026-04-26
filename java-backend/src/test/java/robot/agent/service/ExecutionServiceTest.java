package robot.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import robot.agent.dto.request.SendMessageRequest;
import robot.agent.dto.request.ExecuteRequest;
import robot.agent.dto.response.SendMessageResponse;
import robot.agent.model.Execution;
import robot.agent.model.Session;
import robot.agent.model.WorkflowVersion;
import robot.agent.repository.ExecutionNodeLogRepository;
import robot.agent.repository.ExecutionRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void startExecutionPersistsGatewayMessagePayloadSoSessionQualifiesForHistory() throws Exception {
        SessionService sessionService = mock(SessionService.class);
        WorkflowService workflowService = mock(WorkflowService.class);
        ExecutionRepository executionRepository = mock(ExecutionRepository.class);
        ExecutionNodeLogRepository executionNodeLogRepository = mock(ExecutionNodeLogRepository.class);
        PythonClient pythonClient = mock(PythonClient.class);
        WebSocketPublisher webSocketPublisher = mock(WebSocketPublisher.class);
        AuditService auditService = mock(AuditService.class);
        AccessControlService accessControlService = mock(AccessControlService.class);
        ConfirmationService confirmationService = mock(ConfirmationService.class);
        EntryProtectionService entryProtectionService = mock(EntryProtectionService.class);
        CapabilityRuntimeResolver capabilityRuntimeResolver = mock(CapabilityRuntimeResolver.class);
        CapabilityAuditService capabilityAuditService = mock(CapabilityAuditService.class);

        ExecutionService executionService = new ExecutionService(
                sessionService,
                workflowService,
                executionRepository,
                executionNodeLogRepository,
                pythonClient,
                webSocketPublisher,
                auditService,
                objectMapper,
                accessControlService,
                confirmationService,
                entryProtectionService,
                capabilityRuntimeResolver,
                capabilityAuditService
        );

        Session session = new Session("session-1", 1L, "user-1");
        SendMessageRequest request = objectMapper.convertValue(
                Map.of(
                        "message_id", "msg-1",
                        "message", "hello from websocket",
                        "user_id", "user-1"
                ),
                SendMessageRequest.class
        );
        RoutingDecision routingDecision = new RoutingDecision(
                "start",
                "general_query",
                "v1",
                0.92d,
                0.55d,
                "dynamic:test",
                "entry_rule_and_model",
                List.of("general_query"),
                100
        );
        WorkflowService.RuntimeExecutionBundle runtimeBundle = new WorkflowService.RuntimeExecutionBundle(
                Map.of("nodes", Map.of(), "entry", "start"),
                Map.of(),
                Map.of(),
                Map.of("general_query@v1", Map.of()),
                List.of(),
                List.of(),
                "routing-default"
        );

        when(sessionService.getOrCreateSession("session-1", "user-1")).thenReturn(session);
        when(executionRepository.findBySessionIdAndClientMessageId("session-1", "msg-1")).thenReturn(Optional.empty());
        when(executionRepository.findBySessionIdOrderByCreatedAtDesc("session-1")).thenReturn(List.of());
        when(workflowService.routeMessage(eq("hello from websocket"), eq(null))).thenReturn(routingDecision);
        when(accessControlService.evaluateExecutionAccess("user-1", 1L, "general_query", null, "user-1"))
                .thenReturn(new AccessControlService.AuthorizationDecision(true, "allow", "policy_allow_execution", java.util.Set.of("viewer"), Map.of()));
        when(confirmationService.resolveRequestedToolCode(null, "hello from websocket")).thenReturn(null);
        when(confirmationService.evaluate("session-1", "user-1", "hello from websocket", null, null, false))
                .thenReturn(new ConfirmationService.ConfirmationEvaluation("approved", null, null, null, null));
        when(entryProtectionService.evaluateExecutionStart("user-1", "session-1", "general_query", null))
                .thenReturn(new EntryProtectionService.ProtectionDecision(true, "allowed", null, null, null));
        when(workflowService.buildRuntimeExecutionBundle("general_query", "v1")).thenReturn(runtimeBundle);
        when(capabilityRuntimeResolver.resolveWorkflowDefinition(runtimeBundle.workflowDefinition()))
                .thenReturn(runtimeBundle.workflowDefinition());
        when(executionRepository.save(any(Execution.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionService.updateCurrentExecutionId(eq(session), any(String.class))).thenAnswer(invocation -> {
            session.setCurrentExecutionId(invocation.getArgument(1));
            return session;
        });
        when(pythonClient.execute(any())).thenReturn(Flux.<ServerSentEvent<String>>empty());

        SendMessageResponse response = executionService.startExecution("session-1", request);

        ArgumentCaptor<Execution> executionCaptor = ArgumentCaptor.forClass(Execution.class);
        verify(executionRepository).save(executionCaptor.capture());
        Execution savedExecution = executionCaptor.getValue();
        Map<String, Object> savedInput = objectMapper.readValue(
                savedExecution.getInputVariables(),
                new TypeReference<Map<String, Object>>() {}
        );

        assertThat(response.getSessionId()).isEqualTo("session-1");
        assertThat(response.getExecutionId()).isEqualTo(savedExecution.getId());
        assertThat(savedExecution.getSessionId()).isEqualTo("session-1");
        assertThat(savedInput.get("user_message")).isEqualTo("hello from websocket");
        assertThat(savedInput.get("user_id")).isEqualTo("user-1");
        assertThat(savedInput.get("session_id")).isEqualTo("session-1");
    }

    @Test
    void startExecution_resolvesCapabilityWorkflowDefinitionBeforeDispatch() {
        SessionService sessionService = mock(SessionService.class);
        WorkflowService workflowService = mock(WorkflowService.class);
        ExecutionRepository executionRepository = mock(ExecutionRepository.class);
        ExecutionNodeLogRepository executionNodeLogRepository = mock(ExecutionNodeLogRepository.class);
        PythonClient pythonClient = mock(PythonClient.class);
        WebSocketPublisher webSocketPublisher = mock(WebSocketPublisher.class);
        AuditService auditService = mock(AuditService.class);
        AccessControlService accessControlService = mock(AccessControlService.class);
        ConfirmationService confirmationService = mock(ConfirmationService.class);
        EntryProtectionService entryProtectionService = mock(EntryProtectionService.class);
        CapabilityRuntimeResolver capabilityRuntimeResolver = mock(CapabilityRuntimeResolver.class);
        CapabilityAuditService capabilityAuditService = mock(CapabilityAuditService.class);

        ExecutionService executionService = new ExecutionService(
                sessionService,
                workflowService,
                executionRepository,
                executionNodeLogRepository,
                pythonClient,
                webSocketPublisher,
                auditService,
                objectMapper,
                accessControlService,
                confirmationService,
                entryProtectionService,
                capabilityRuntimeResolver,
                capabilityAuditService
        );

        Session session = new Session("session-1", 1L, "user-1");
        SendMessageRequest request = objectMapper.convertValue(
                Map.of(
                        "message_id", "msg-1",
                        "message", "run capability",
                        "user_id", "user-1"
                ),
                SendMessageRequest.class
        );
        RoutingDecision routingDecision = new RoutingDecision(
                "start",
                "cap_workflow",
                "draft",
                1.0d,
                0.0d,
                "manual",
                "canvas_selected_workflow",
                List.of("cap_workflow"),
                100
        );
        Map<String, Object> rawDefinition = Map.of(
                "entry", "start",
                "nodes", Map.of(
                        "call_health", Map.of(
                                "id", "call_health",
                                "type", "tool",
                                "config", Map.of("invoke_type", "capability")
                        )
                )
        );
        Map<String, Object> resolvedDefinition = Map.of(
                "entry", "start",
                "nodes", Map.of(
                        "call_health", Map.of(
                                "id", "call_health",
                                "type", "tool",
                                "config", Map.of(
                                        "invoke_type", "api",
                                        "tool_code", "health_check",
                                        "url", "http://127.0.0.1:8080/actuator/health",
                                        "method", "GET"
                                )
                        )
                )
        );
        WorkflowService.RuntimeExecutionBundle runtimeBundle = new WorkflowService.RuntimeExecutionBundle(
                rawDefinition,
                Map.of(),
                Map.of(),
                Map.of("cap_workflow@draft", Map.of()),
                List.of(),
                List.of(),
                "routing-default"
        );

        when(sessionService.getOrCreateSession("session-1", "user-1")).thenReturn(session);
        when(executionRepository.findBySessionIdAndClientMessageId("session-1", "msg-1")).thenReturn(Optional.empty());
        when(executionRepository.findBySessionIdOrderByCreatedAtDesc("session-1")).thenReturn(List.of());
        when(workflowService.routeMessage(eq("run capability"), eq(null))).thenReturn(routingDecision);
        when(accessControlService.evaluateExecutionAccess("user-1", 1L, "cap_workflow", null, "user-1"))
                .thenReturn(new AccessControlService.AuthorizationDecision(true, "allow", "policy_allow_execution", java.util.Set.of("viewer"), Map.of()));
        when(confirmationService.resolveRequestedToolCode(null, "run capability")).thenReturn(null);
        when(confirmationService.evaluate("session-1", "user-1", "run capability", null, null, false))
                .thenReturn(new ConfirmationService.ConfirmationEvaluation("approved", null, null, null, null));
        when(entryProtectionService.evaluateExecutionStart("user-1", "session-1", "cap_workflow", null))
                .thenReturn(new EntryProtectionService.ProtectionDecision(true, "allowed", null, null, null));
        when(workflowService.buildRuntimeExecutionBundle("cap_workflow", "draft")).thenReturn(runtimeBundle);
        when(capabilityRuntimeResolver.resolveWorkflowDefinition(rawDefinition)).thenReturn(resolvedDefinition);
        when(executionRepository.save(any(Execution.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionService.updateCurrentExecutionId(eq(session), any(String.class))).thenAnswer(invocation -> {
            session.setCurrentExecutionId(invocation.getArgument(1));
            return session;
        });
        when(pythonClient.execute(any())).thenReturn(Flux.<ServerSentEvent<String>>empty());

        executionService.startExecution("session-1", request);

        ArgumentCaptor<ExecuteRequest> requestCaptor = ArgumentCaptor.forClass(ExecuteRequest.class);
        verify(pythonClient).execute(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getWorkflowDefinition()).isEqualTo(resolvedDefinition);
    }

    @Test
    void startExecution_explicitWorkflowDispatchesPublishedV2Snapshot() {
        SessionService sessionService = mock(SessionService.class);
        WorkflowService workflowService = mock(WorkflowService.class);
        ExecutionRepository executionRepository = mock(ExecutionRepository.class);
        ExecutionNodeLogRepository executionNodeLogRepository = mock(ExecutionNodeLogRepository.class);
        PythonClient pythonClient = mock(PythonClient.class);
        WebSocketPublisher webSocketPublisher = mock(WebSocketPublisher.class);
        AuditService auditService = mock(AuditService.class);
        AccessControlService accessControlService = mock(AccessControlService.class);
        ConfirmationService confirmationService = mock(ConfirmationService.class);
        EntryProtectionService entryProtectionService = mock(EntryProtectionService.class);
        CapabilityRuntimeResolver capabilityRuntimeResolver = mock(CapabilityRuntimeResolver.class);
        CapabilityAuditService capabilityAuditService = mock(CapabilityAuditService.class);

        ExecutionService executionService = new ExecutionService(
                sessionService,
                workflowService,
                executionRepository,
                executionNodeLogRepository,
                pythonClient,
                webSocketPublisher,
                auditService,
                objectMapper,
                accessControlService,
                confirmationService,
                entryProtectionService,
                capabilityRuntimeResolver,
                capabilityAuditService
        );

        Session session = new Session("session-1", 1L, "user-1");
        SendMessageRequest request = objectMapper.convertValue(
                Map.of(
                        "message_id", "msg-explicit",
                        "message", "执行显式工作流",
                        "user_id", "user-1",
                        "workflow_code", "travel_assistant",
                        "workflow_version", "v20260426",
                        "workflow_definition", Map.of("entry", "legacy")
                ),
                SendMessageRequest.class
        );

        Map<String, Object> publishedDefinition = Map.of(
                "schema_version", "workflow-designer/v2",
                "main_graph_id", "main",
                "graphs", Map.of(
                        "main", Map.of(
                                "graph_id", "main",
                                "graph_type", "main",
                                "entry_node_id", "start",
                                "nodes", Map.of(
                                        "start", Map.of("id", "start", "type", "start", "config", Map.of("prompt", "开始")),
                                        "end", Map.of("id", "end", "type", "end", "config", Map.of("prompt", "结束", "output_format", Map.of()))
                                ),
                                "edges", List.of(Map.of("id", "e1", "source", "start", "target", "end"))
                        )
                )
        );
        WorkflowService.RuntimeExecutionBundle runtimeBundle = new WorkflowService.RuntimeExecutionBundle(
                publishedDefinition,
                Map.of(),
                Map.of("legacy", true),
                Map.of("travel_assistant@v20260426", publishedDefinition),
                List.of(),
                List.of(),
                "routing-default"
        );

        when(sessionService.getOrCreateSession("session-1", "user-1")).thenReturn(session);
        when(executionRepository.findBySessionIdAndClientMessageId("session-1", "msg-explicit")).thenReturn(Optional.empty());
        when(executionRepository.findBySessionIdOrderByCreatedAtDesc("session-1")).thenReturn(List.of());
        when(workflowService.requirePublishedWorkflowVersion("travel_assistant", "v20260426")).thenReturn(new WorkflowVersion());
        when(accessControlService.evaluateExecutionAccess("user-1", 1L, "travel_assistant", null, "user-1"))
                .thenReturn(new AccessControlService.AuthorizationDecision(true, "allow", "policy_allow_execution", java.util.Set.of("viewer"), Map.of()));
        when(confirmationService.resolveRequestedToolCode(null, "执行显式工作流")).thenReturn(null);
        when(confirmationService.evaluate("session-1", "user-1", "执行显式工作流", null, null, false))
                .thenReturn(new ConfirmationService.ConfirmationEvaluation("approved", null, null, null, null));
        when(entryProtectionService.evaluateExecutionStart("user-1", "session-1", "travel_assistant", null))
                .thenReturn(new EntryProtectionService.ProtectionDecision(true, "allowed", null, null, null));
        when(workflowService.buildRuntimeExecutionBundleForExplicitExecution("travel_assistant", "v20260426")).thenReturn(runtimeBundle);
        when(capabilityRuntimeResolver.resolveWorkflowDefinition(publishedDefinition)).thenReturn(publishedDefinition);
        when(executionRepository.save(any(Execution.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionService.updateCurrentExecutionId(eq(session), any(String.class))).thenAnswer(invocation -> {
            session.setCurrentExecutionId(invocation.getArgument(1));
            return session;
        });
        when(pythonClient.execute(any())).thenReturn(Flux.<ServerSentEvent<String>>empty());

        executionService.startExecution("session-1", request);

        ArgumentCaptor<ExecuteRequest> requestCaptor = ArgumentCaptor.forClass(ExecuteRequest.class);
        verify(pythonClient).execute(requestCaptor.capture());
        verify(workflowService).buildRuntimeExecutionBundleForExplicitExecution("travel_assistant", "v20260426");
        assertThat(requestCaptor.getValue().getWorkflowCode()).isEqualTo("travel_assistant");
        assertThat(requestCaptor.getValue().getWorkflowDefinition()).containsEntry("schema_version", "workflow-designer/v2");
        assertThat(requestCaptor.getValue().getWorkflowDefinition()).containsKey("graphs");
    }
}
