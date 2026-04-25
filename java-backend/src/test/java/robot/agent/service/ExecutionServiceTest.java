package robot.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import robot.agent.dto.request.SendMessageRequest;
import robot.agent.dto.response.SendMessageResponse;
import robot.agent.model.Execution;
import robot.agent.model.Session;
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
                entryProtectionService
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
}
