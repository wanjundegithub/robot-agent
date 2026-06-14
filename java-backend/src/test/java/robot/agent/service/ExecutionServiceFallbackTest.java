package robot.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import robot.agent.config.ChatFallbackProperties;
import robot.agent.dto.response.SessionMessageResponse;
import robot.agent.model.Execution;
import robot.agent.model.ExecutionStatus;
import robot.agent.model.Session;
import robot.agent.repository.ExecutionNodeLogRepository;
import robot.agent.repository.ExecutionRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutionServiceFallbackTest {

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
    private robot.agent.channel.core.UserConnectionManager userConnectionManager;

    @Mock
    private AuditService auditService;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private ConfirmationService confirmationService;

    @Mock
    private EntryProtectionService entryProtectionService;

    @Mock
    private robot.agent.apicenter.service.ApiRuntimeResolver apiRuntimeResolver;

    private ChatFallbackProperties chatFallbackProperties;

    private ExecutionService executionService;

    @BeforeEach
    void setUp() {
        chatFallbackProperties = new ChatFallbackProperties();
        executionService = new ExecutionService(
                sessionService,
                workflowService,
                executionRepository,
                executionNodeLogRepository,
                pythonClient,
                userConnectionManager,
                auditService,
                new ObjectMapper(),
                accessControlService,
                confirmationService,
                entryProtectionService,
                apiRuntimeResolver,
                chatFallbackProperties
        );
    }

    @Test
    void historyUsesFallbackMessageInsteadOfModelErrorWhenExecutionFails() {
        Session session = new Session();
        session.setId("session-1");
        Execution execution = new Execution();
        execution.setId("execution-1");
        execution.setSessionId("session-1");
        execution.setStatus(ExecutionStatus.FAILED);
        execution.setError("Model execution failed: 503 upstream unavailable");
        execution.setInputVariables("{\"user_message\":\"预定酒店\"}");
        execution.setCreatedAt(LocalDateTime.now());
        execution.setCompletedAt(LocalDateTime.now());

        when(sessionService.getSessionEntity("session-1")).thenReturn(session);
        when(executionRepository.findBySessionIdOrderByCreatedAtAsc("session-1")).thenReturn(List.of(execution));

        List<SessionMessageResponse> history = executionService.getSessionMessageHistory("session-1");

        assertThat(history).hasSize(2);
        assertThat(history.get(1).getType()).isEqualTo("ai");
        assertThat(history.get(1).getContent()).isEqualTo("当前模型服务暂时不可用，请稍后再试。");
        assertThat(history.get(1).getContent()).doesNotContain("503", "Model execution failed");
    }

    @Test
    void historyUsesConfiguredFallbackMessageWhenExecutionFails() {
        chatFallbackProperties.setModelUnavailableMessage("模型开小差了，请稍后再试。 ");
        Session session = new Session();
        session.setId("session-1");
        Execution execution = new Execution();
        execution.setId("execution-1");
        execution.setSessionId("session-1");
        execution.setStatus(ExecutionStatus.FAILED);
        execution.setError("Model execution failed: invalid api key");
        execution.setInputVariables("{\"user_message\":\"预定酒店\"}");
        execution.setCreatedAt(LocalDateTime.now());

        when(sessionService.getSessionEntity("session-1")).thenReturn(session);
        when(executionRepository.findBySessionIdOrderByCreatedAtAsc("session-1")).thenReturn(List.of(execution));

        List<SessionMessageResponse> history = executionService.getSessionMessageHistory("session-1");

        assertThat(history.get(1).getType()).isEqualTo("ai");
        assertThat(history.get(1).getContent()).isEqualTo("模型开小差了，请稍后再试。");
        assertThat(history.get(1).getContent()).doesNotContain("invalid api key");
    }

    @Test
    void chatResumeStoresSlotAnswerInHistory() {
        Session session = new Session();
        session.setId("session-1");
        session.setWorkspaceId(1L);
        session.setUserId("user-1");
        session.setCurrentExecutionId("execution-1");

        Execution execution = new Execution();
        execution.setId("execution-1");
        execution.setSessionId("session-1");
        execution.setStatus(ExecutionStatus.WAITING_USER);
        execution.setInputVariables("{\"user_message\":\"查询产品\"}");
        execution.setCreatedAt(LocalDateTime.now());

        robot.agent.dto.request.SendMessageRequest request = new robot.agent.dto.request.SendMessageRequest();
        request.setUserId("user-1");
        request.setContent("iPhone 15");

        when(sessionService.getOrCreateSession("session-1", "user-1")).thenReturn(session);
        when(executionRepository.findById("execution-1")).thenReturn(Optional.of(execution));
        doReturn(reactor.core.publisher.Mono.empty()).when(pythonClient).submitSlotAnswer(any(), any());

        executionService.startExecution("session-1", request);

        verify(executionRepository).save(argThat(saved ->
                saved != null
                        && saved.getInputVariables() != null
                        && saved.getInputVariables().contains("slot_user_messages")
                        && saved.getInputVariables().contains("iPhone 15")
        ));
    }

    @Test
    void messageHistoryIncludesSlotAnswerMessages() {
        Session session = new Session();
        session.setId("session-1");

        Execution execution = new Execution();
        execution.setId("execution-1");
        execution.setSessionId("session-1");
        execution.setStatus(ExecutionStatus.WAITING_USER);
        execution.setInputVariables("{\"user_message\":\"查询产品\",\"slot_user_messages\":[\"iPhone 15\"]}");
        execution.setOutputVariables("{\"message\":\"请提供要查询的产品名称。\"}");
        execution.setCreatedAt(LocalDateTime.now());

        when(sessionService.getSessionEntity("session-1")).thenReturn(session);
        when(executionRepository.findBySessionIdOrderByCreatedAtAsc("session-1")).thenReturn(List.of(execution));

        List<SessionMessageResponse> history = executionService.getSessionMessageHistory("session-1");

        assertThat(history).extracting(SessionMessageResponse::getType)
                .containsExactly("user", "user", "ai");
        assertThat(history).extracting(SessionMessageResponse::getContent)
                .containsExactly("查询产品", "iPhone 15", "请提供要查询的产品名称。");
    }
}
