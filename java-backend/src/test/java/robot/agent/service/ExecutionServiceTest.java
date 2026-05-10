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
import robot.agent.model.Workflow;
import robot.agent.model.Session;
import robot.agent.model.WorkflowVersion;
import robot.agent.repository.ExecutionNodeLogRepository;
import robot.agent.repository.ExecutionRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
                100,
                "general_query",
                "workflow",
                "general_query",
                null,
                List.of()
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
                100,
                "cap_workflow",
                "workflow",
                "cap_workflow",
                null,
                List.of()
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
        assertThat(requestCaptor.getValue().getModelRecords()).isEmpty();
        assertThat(requestCaptor.getValue().getRoutingModelCode()).isEqualTo("routing-default");
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
        assertThat(requestCaptor.getValue().getModelRecords()).isEmpty();
        assertThat(requestCaptor.getValue().getRoutingModelCode()).isEqualTo("routing-default");
    }

    @Test
    void startExecution_storesSecondaryIntentCandidatesInSessionVariables() throws Exception {
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
                        "message_id", "msg-queue-1",
                        "message", "query order and cancel booking",
                        "user_id", "user-1"
                ),
                SendMessageRequest.class
        );
        RoutingDecision.IntentCandidate secondary = new RoutingDecision.IntentCandidate(
                "intent_cancel_booking",
                "workflow",
                "booking_cancel",
                0.71d,
                "rag",
                "cancel booking"
        );
        RoutingDecision routingDecision = new RoutingDecision(
                "start",
                "order_query",
                "v1",
                0.92d,
                0.8d,
                "rag_accept_threshold",
                "rag_match",
                List.of("order_query", "booking_cancel"),
                50,
                "intent_order_query",
                "workflow",
                "order_query",
                null,
                List.of(secondary)
        );
        WorkflowService.RuntimeExecutionBundle runtimeBundle = new WorkflowService.RuntimeExecutionBundle(
                Map.of("nodes", Map.of(), "entry", "start"),
                Map.of(),
                Map.of(),
                Map.of("order_query@v1", Map.of()),
                List.of(),
                List.of(),
                "routing-default"
        );

        when(sessionService.getOrCreateSession("session-1", "user-1")).thenReturn(session);
        when(executionRepository.findBySessionIdAndClientMessageId("session-1", "msg-queue-1")).thenReturn(Optional.empty());
        when(executionRepository.findBySessionIdOrderByCreatedAtDesc("session-1")).thenReturn(List.of());
        when(workflowService.routeMessage(eq("query order and cancel booking"), eq(null))).thenReturn(routingDecision);
        when(accessControlService.evaluateExecutionAccess("user-1", 1L, "order_query", null, "user-1"))
                .thenReturn(new AccessControlService.AuthorizationDecision(true, "allow", "policy_allow_execution", java.util.Set.of("viewer"), Map.of()));
        when(confirmationService.resolveRequestedToolCode(null, "query order and cancel booking")).thenReturn(null);
        when(confirmationService.evaluate("session-1", "user-1", "query order and cancel booking", null, null, false))
                .thenReturn(new ConfirmationService.ConfirmationEvaluation("approved", null, null, null, null));
        when(entryProtectionService.evaluateExecutionStart("user-1", "session-1", "order_query", null))
                .thenReturn(new EntryProtectionService.ProtectionDecision(true, "allowed", null, null, null));
        when(workflowService.buildRuntimeExecutionBundle("order_query", "v1")).thenReturn(runtimeBundle);
        when(capabilityRuntimeResolver.resolveWorkflowDefinition(runtimeBundle.workflowDefinition()))
                .thenReturn(runtimeBundle.workflowDefinition());
        when(executionRepository.save(any(Execution.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionService.updateCurrentExecutionId(eq(session), any(String.class))).thenAnswer(invocation -> {
            session.setCurrentExecutionId(invocation.getArgument(1));
            return session;
        });
        when(pythonClient.execute(any())).thenReturn(Flux.<ServerSentEvent<String>>empty());

        executionService.startExecution("session-1", request);

        Map<String, Object> variables = objectMapper.readValue(
                session.getVariables(),
                new TypeReference<Map<String, Object>>() {}
        );
        assertThat(variables).containsKey("intent_candidate_queue");
        List<Map<String, Object>> queue = objectMapper.convertValue(
                variables.get("intent_candidate_queue"),
                new TypeReference<List<Map<String, Object>>>() {}
        );
        assertThat(queue).hasSize(1);
        assertThat(queue.get(0)).containsEntry("targetCode", "booking_cancel");
    }

    @Test
    void startExecution_acceptActionStartsQueuedWorkflow() {
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

        List<Map<String, Object>> queue = List.of(
                Map.of(
                        "intentCode", "intent_cancel_booking",
                        "targetType", "workflow",
                        "targetCode", "booking_cancel",
                        "confidence", 0.71d,
                        "source", "rag",
                        "evidence", "cancel booking"
                )
        );
        Session session = new Session("session-1", 1L, "user-1");
        session.setVariables(writeVariables(Map.of("intent_candidate_queue", queue)));
        SendMessageRequest request = objectMapper.convertValue(
                Map.of(
                        "message_id", "msg-accept-1",
                        "message", "继续",
                        "user_id", "user-1",
                        "intent_candidate_action", "accept",
                        "intent_candidate_target_code", "booking_cancel"
                ),
                SendMessageRequest.class
        );
        WorkflowService.RuntimeExecutionBundle runtimeBundle = new WorkflowService.RuntimeExecutionBundle(
                Map.of("nodes", Map.of(), "entry", "start"),
                Map.of(),
                Map.of(),
                Map.of("booking_cancel@v1", Map.of()),
                List.of(),
                List.of(),
                "routing-default"
        );
        when(sessionService.getOrCreateSession("session-1", "user-1")).thenReturn(session);
        when(executionRepository.findBySessionIdAndClientMessageId("session-1", "msg-accept-1")).thenReturn(Optional.empty());
        when(executionRepository.findBySessionIdOrderByCreatedAtDesc("session-1")).thenReturn(List.of());
        when(workflowService.getWorkflowByCode("booking_cancel"))
                .thenReturn(robot.agent.dto.response.WorkflowResponse.fromEntity(workflow("booking_cancel", "v1")));
        when(workflowService.requirePublishedWorkflowVersion("booking_cancel", "v1"))
                .thenReturn(new WorkflowVersion());
        when(accessControlService.evaluateExecutionAccess("user-1", 1L, "booking_cancel", null, "user-1"))
                .thenReturn(new AccessControlService.AuthorizationDecision(true, "allow", "policy_allow_execution", java.util.Set.of("viewer"), Map.of()));
        when(confirmationService.resolveRequestedToolCode(null, "继续")).thenReturn(null);
        when(confirmationService.evaluate("session-1", "user-1", "继续", null, null, false))
                .thenReturn(new ConfirmationService.ConfirmationEvaluation("approved", null, null, null, null));
        when(entryProtectionService.evaluateExecutionStart("user-1", "session-1", "booking_cancel", null))
                .thenReturn(new EntryProtectionService.ProtectionDecision(true, "allowed", null, null, null));
        when(workflowService.buildRuntimeExecutionBundleForExplicitExecution("booking_cancel", "v1")).thenReturn(runtimeBundle);
        when(capabilityRuntimeResolver.resolveWorkflowDefinition(runtimeBundle.workflowDefinition()))
                .thenReturn(runtimeBundle.workflowDefinition());
        when(executionRepository.save(any(Execution.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionService.updateCurrentExecutionId(eq(session), any(String.class))).thenAnswer(invocation -> {
            session.setCurrentExecutionId(invocation.getArgument(1));
            return session;
        });
        when(pythonClient.execute(any())).thenReturn(Flux.<ServerSentEvent<String>>empty());

        SendMessageResponse response = executionService.startExecution("session-1", request);

        verify(workflowService, never()).routeMessage(any(), any());
        verify(executionRepository, never()).findBySessionIdAndClientMessageId("session-1", "msg-accept-1");
        assertThat(response.getWorkflowCode()).isEqualTo("booking_cancel");
        assertThat(response.getRouteReason()).isEqualTo("candidate_confirmation");
    }

    @Test
    void startExecution_rejectActionReturnsNextCandidateConfirmation() {
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

        List<Map<String, Object>> queue = List.of(
                Map.of(
                        "intentCode", "intent_cancel_booking",
                        "targetType", "workflow",
                        "targetCode", "booking_cancel",
                        "confidence", 0.71d,
                        "source", "rag",
                        "evidence", "cancel booking"
                ),
                Map.of(
                        "intentCode", "intent_order_query",
                        "targetType", "workflow",
                        "targetCode", "order_query",
                        "confidence", 0.66d,
                        "source", "rag",
                        "evidence", "query order"
                )
        );
        Session session = new Session("session-1", 1L, "user-1");
        session.setVariables(writeVariables(Map.of("intent_candidate_queue", queue)));
        SendMessageRequest request = objectMapper.convertValue(
                Map.of(
                        "message_id", "msg-reject-1",
                        "message", "跳过",
                        "user_id", "user-1",
                        "intent_candidate_action", "reject",
                        "intent_candidate_target_code", "booking_cancel"
                ),
                SendMessageRequest.class
        );
        when(sessionService.getOrCreateSession("session-1", "user-1")).thenReturn(session);
        when(workflowService.getWorkflowByCode("order_query"))
                .thenReturn(robot.agent.dto.response.WorkflowResponse.fromEntity(workflow("order_query", "v2")));

        SendMessageResponse response = executionService.startExecution("session-1", request);

        verify(workflowService, never()).routeMessage(any(), any());
        verify(pythonClient, never()).execute(any());
        assertThat(response.getStatus()).isEqualTo("candidate_confirmation_required");
        assertThat(response.getWorkflowCode()).isEqualTo("order_query");
        assertThat(response.getIntentCandidateQueue()).hasSize(1);
    }

    @Test
    void startExecution_clarificationRequiredReturnsWithoutExecutionId() {
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
                        "message_id", "msg-clarify-1",
                        "message", "???",
                        "user_id", "user-1"
                ),
                SendMessageRequest.class
        );
        RoutingDecision routingDecision = new RoutingDecision(
                "clarification_required",
                null,
                null,
                0.0d,
                0.7d,
                "llm_accept_threshold",
                "llm_no_match",
                List.of(),
                0,
                null,
                "workflow",
                null,
                "请问你想办理哪类业务？",
                List.of()
        );
        when(sessionService.getOrCreateSession("session-1", "user-1")).thenReturn(session);
        when(executionRepository.findBySessionIdAndClientMessageId("session-1", "msg-clarify-1")).thenReturn(Optional.empty());
        when(executionRepository.findBySessionIdOrderByCreatedAtDesc("session-1")).thenReturn(List.of());
        when(workflowService.routeMessage("???", null)).thenReturn(routingDecision);

        SendMessageResponse response = executionService.startExecution("session-1", request);

        verify(pythonClient, never()).execute(any());
        assertThat(response.getExecutionId()).isNull();
        assertThat(response.getStatus()).isEqualTo("clarification_required");
        assertThat(response.getClarificationQuestion()).isEqualTo("请问你想办理哪类业务？");
    }

    private String writeVariables(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(new LinkedHashMap<>(data));
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private Workflow workflow(String code, String currentVersion) {
        Workflow workflow = new Workflow();
        workflow.setWorkflowCode(code);
        workflow.setCurrentVersion(currentVersion);
        return workflow;
    }
}
