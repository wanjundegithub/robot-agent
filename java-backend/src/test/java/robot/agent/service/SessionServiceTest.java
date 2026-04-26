package robot.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import robot.agent.dto.response.SessionResponse;
import robot.agent.model.Execution;
import robot.agent.model.Session;
import robot.agent.model.SessionStatus;
import robot.agent.repository.ExecutionRepository;
import robot.agent.repository.SessionRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionServiceTest {

    @Test
    void getSessionsByUserIdExcludesDeletedSessionsAndEmptySessions() {
        SessionRepository sessionRepository = mock(SessionRepository.class);
        ExecutionRepository executionRepository = mock(ExecutionRepository.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        Session deletedSession = session("deleted-session", "user-1", SessionStatus.DELETED, LocalDateTime.parse("2026-04-25T12:00:00"));
        Session emptySession = session("empty-session", "user-1", SessionStatus.ACTIVE, LocalDateTime.parse("2026-04-25T11:00:00"));
        Session realSession = session("real-session", "user-1", SessionStatus.ACTIVE, LocalDateTime.parse("2026-04-25T10:00:00"));
        when(sessionRepository.findByUserIdOrderByLastActivityAtDesc("user-1"))
                .thenReturn(List.of(deletedSession, emptySession, realSession));
        when(executionRepository.findBySessionIdOrderByCreatedAtAsc("empty-session"))
                .thenReturn(List.of(execution("exec-empty", "empty-session", "{\"user_message\":\"   \"}")));
        when(executionRepository.findBySessionIdOrderByCreatedAtAsc("real-session"))
                .thenReturn(List.of(execution("exec-real", "real-session", "{\"user_message\":\"hello\"}")));

        SessionSchemaRepairService sessionSchemaRepairService = mock(SessionSchemaRepairService.class);
        SessionService sessionService = new SessionService(sessionRepository, executionRepository, new ObjectMapper(), jdbcTemplate, sessionSchemaRepairService);

        List<SessionResponse> sessions = sessionService.getSessionsByUserId("user-1");

        assertThat(sessions)
                .extracting(SessionResponse::getId)
                .containsExactly("real-session");
        assertThat(sessions)
                .extracting(SessionResponse::getStatus)
                .containsExactly(SessionStatus.ACTIVE);
    }

    @Test
    void getSessionsByUserIdReturnsSessionWhenPersistedExecutionHasUserMessage() {
        SessionRepository sessionRepository = mock(SessionRepository.class);
        ExecutionRepository executionRepository = mock(ExecutionRepository.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        Session realSession = session("real-session", "user-1", SessionStatus.ACTIVE, LocalDateTime.parse("2026-04-25T10:00:00"));
        when(sessionRepository.findByUserIdOrderByLastActivityAtDesc("user-1"))
                .thenReturn(List.of(realSession));
        when(executionRepository.findBySessionIdOrderByCreatedAtAsc("real-session"))
                .thenReturn(List.of(execution("exec-real", "real-session", "{\"user_message\":\"hello\"}")));

        SessionSchemaRepairService sessionSchemaRepairService = mock(SessionSchemaRepairService.class);
        SessionService sessionService = new SessionService(sessionRepository, executionRepository, new ObjectMapper(), jdbcTemplate, sessionSchemaRepairService);

        List<SessionResponse> sessions = sessionService.getSessionsByUserId("user-1");

        assertThat(sessions)
                .extracting(SessionResponse::getId)
                .containsExactly("real-session");
    }

    @Test
    void getSessionsByUserIdIncludesActiveAndClosedSessionsWithRealUserMessages() {
        SessionRepository sessionRepository = mock(SessionRepository.class);
        ExecutionRepository executionRepository = mock(ExecutionRepository.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        Session newerClosedSession = session("closed-session", "user-1", SessionStatus.CLOSED, LocalDateTime.parse("2026-04-25T11:00:00"));
        Session olderActiveSession = session("active-session", "user-1", SessionStatus.ACTIVE, LocalDateTime.parse("2026-04-25T10:00:00"));
        when(sessionRepository.findByUserIdOrderByLastActivityAtDesc("user-1"))
                .thenReturn(List.of(newerClosedSession, olderActiveSession));
        when(executionRepository.findBySessionIdOrderByCreatedAtAsc("closed-session"))
                .thenReturn(List.of(execution("exec-closed", "closed-session", "{\"user_message\":\"done\"}")));
        when(executionRepository.findBySessionIdOrderByCreatedAtAsc("active-session"))
                .thenReturn(List.of(execution("exec-active", "active-session", "{\"content\":\"working\"}")));

        SessionSchemaRepairService sessionSchemaRepairService = mock(SessionSchemaRepairService.class);
        SessionService sessionService = new SessionService(sessionRepository, executionRepository, new ObjectMapper(), jdbcTemplate, sessionSchemaRepairService);

        List<SessionResponse> sessions = sessionService.getSessionsByUserId("user-1");

        assertThat(sessions)
                .extracting(SessionResponse::getId)
                .containsExactly("closed-session", "active-session");
        assertThat(sessions)
                .extracting(SessionResponse::getStatus)
                .containsExactly(SessionStatus.CLOSED, SessionStatus.ACTIVE);
    }

    @Test
    void deleteSessionSoftDeletesBySettingStatusDeleted() {
        SessionRepository sessionRepository = mock(SessionRepository.class);
        ExecutionRepository executionRepository = mock(ExecutionRepository.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        Session activeSession = session("session-1", "user-1", SessionStatus.ACTIVE, LocalDateTime.parse("2026-04-25T10:00:00"));
        when(sessionRepository.findById("session-1")).thenReturn(java.util.Optional.of(activeSession));
        when(jdbcTemplate.update(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(1);

        SessionSchemaRepairService sessionSchemaRepairService = mock(SessionSchemaRepairService.class);
        SessionService sessionService = new SessionService(sessionRepository, executionRepository, new ObjectMapper(), jdbcTemplate, sessionSchemaRepairService);

        sessionService.closeSession("session-1");

        assertThat(activeSession.getStatus()).isEqualTo(SessionStatus.DELETED);
        verify(jdbcTemplate).update(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void closeSessionRepairsLegacyStatusEnumAndRetriesWhenDeletedValueIsRejected() {
        SessionRepository sessionRepository = mock(SessionRepository.class);
        ExecutionRepository executionRepository = mock(ExecutionRepository.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SessionSchemaRepairService sessionSchemaRepairService = mock(SessionSchemaRepairService.class);
        Session activeSession = session("session-1", "user-1", SessionStatus.ACTIVE, LocalDateTime.parse("2026-04-25T10:00:00"));
        when(sessionRepository.findById("session-1")).thenReturn(java.util.Optional.of(activeSession));

        AtomicInteger updateCount = new AtomicInteger();
        when(jdbcTemplate.update(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            if (updateCount.getAndIncrement() == 0) {
                throw new DataIntegrityViolationException("Data truncated for column 'status' at row 1");
            }
            return 1;
        });

        SessionService sessionService = new SessionService(sessionRepository, executionRepository, new ObjectMapper(), jdbcTemplate, sessionSchemaRepairService);

        sessionService.closeSession("session-1");

        assertThat(activeSession.getStatus()).isEqualTo(SessionStatus.DELETED);
        verify(sessionSchemaRepairService).ensureDeletedStatusSupported();
        verify(jdbcTemplate, times(2)).update(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private Session session(String id, String userId, SessionStatus status, LocalDateTime lastActivityAt) {
        Session session = new Session(id, 1L, userId);
        session.setStatus(status);
        session.setLastActivityAt(lastActivityAt);
        return session;
    }

    private Execution execution(String id, String sessionId, String inputVariables) {
        Execution execution = new Execution();
        execution.setId(id);
        execution.setSessionId(sessionId);
        execution.setInputVariables(inputVariables);
        execution.setCreatedAt(LocalDateTime.parse("2026-04-25T10:00:00"));
        return execution;
    }
}
