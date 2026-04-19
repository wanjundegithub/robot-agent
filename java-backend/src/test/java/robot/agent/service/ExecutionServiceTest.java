package robot.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import robot.agent.dto.request.ExecuteRequest;
import robot.agent.dto.request.SendMessageRequest;
import robot.agent.dto.response.SendMessageResponse;
import robot.agent.model.Execution;
import robot.agent.model.Session;
import robot.agent.repository.ExecutionNodeLogRepository;
import robot.agent.repository.ExecutionRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutionServiceTest {

    @Mock
    private SessionService sessionService;

    @Mock
    private WorkflowService workflowService;

    @Mock
    private ExecutionRepository executionRepository;

    @Mock
    private ExecutionNodeLogRepository executionNodeLogRepository;

    @Mock
    private PythonClient pythonClient;

    @Mock
    private WebSocketPublisher webSocketPublisher;

    @Mock
    private AuditService auditService;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private ConfirmationService confirmationService;

    @Mock
    private EntryProtectionService entryProtectionService;

    @Test
    void startExecutionUsesPublishedWorkflowWhenExplicitBindingHasNoDraftDefinition() {
        ObjectMapper objectMapper = new ObjectMapper();
        ExecutionService service = new ExecutionService(
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

        Session session = new Session("sess_1", 1L, "demo-user");
        when(sessionService.getOrCreateSession("sess_1", "demo-user")).thenReturn(session);
        when(executionRepository.findBySessionIdAndClientMessageId("sess_1", "msg_1")).thenReturn(java.util.Optional.empty());
        when(executionRepository.findBySessionIdOrderByCreatedAtDesc("sess_1")).thenReturn(List.of());
        when(confirmationService.resolveRequestedToolCode(null, "你好")).thenReturn(null);
        when(accessControlService.evaluateExecutionAccess("demo-user", 1L, "workflow_demo", null, "demo-user"))
                .thenReturn(new AccessControlService.AuthorizationDecision(true, "allow", "policy_allow_execution", Set.of("viewer"), Map.of()));
        when(confirmationService.evaluate("sess_1", "demo-user", "你好", null, null, false))
                .thenReturn(new ConfirmationService.ConfirmationEvaluation("approved", null, null, null, null));
        when(entryProtectionService.evaluateExecutionStart("demo-user", "sess_1", "workflow_demo", null))
                .thenReturn(new EntryProtectionService.ProtectionDecision(true, "allowed", null, null, null));
        when(workflowService.buildRuntimeExecutionBundle("workflow_demo", "v1"))
                .thenReturn(new WorkflowService.RuntimeExecutionBundle(
                        new LinkedHashMap<>(Map.of("nodes", Map.of())),
                        new LinkedHashMap<>(),
                        new LinkedHashMap<>(),
                        new LinkedHashMap<>(),
                        List.of(),
                        List.of(),
                        "intent-router-v1"
                ));
        when(executionRepository.save(any(Execution.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionService.updateCurrentExecutionId(eq(session), any())).thenReturn(session);
        when(pythonClient.execute(any(ExecuteRequest.class))).thenReturn(Flux.empty());

        SendMessageRequest request = new SendMessageRequest();
        request.setMessageId("msg_1");
        request.setContent("你好");
        request.setUserId("demo-user");
        request.setWorkflowCode("workflow_demo");
        request.setWorkflowVersion("v1");

        SendMessageResponse response = service.startExecution("sess_1", request);

        assertThat(response.getStatus()).isEqualTo("running");
        assertThat(response.getWorkflowCode()).isEqualTo("workflow_demo");
        assertThat(response.getWorkflowVersion()).isEqualTo("v1");
        verify(workflowService).buildRuntimeExecutionBundle("workflow_demo", "v1");
        verify(workflowService, never()).buildRuntimeExecutionBundle(eq("workflow_demo"), eq("v1"), any(), any(), any());

        ArgumentCaptor<ExecuteRequest> requestCaptor = ArgumentCaptor.forClass(ExecuteRequest.class);
        verify(pythonClient).execute(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getWorkflowCode()).isEqualTo("workflow_demo");
        assertThat(requestCaptor.getValue().getWorkflowVersion()).isEqualTo("v1");
    }
}
