package robot.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import robot.agent.common.ApplicationConstants;
import robot.agent.dto.request.CreateSessionRequest;
import robot.agent.dto.response.SessionResponse;
import robot.agent.mapper.SessionMapper;
import robot.agent.model.Execution;
import robot.agent.model.Session;
import robot.agent.model.SessionStatus;
import robot.agent.repository.ExecutionRepository;
import robot.agent.repository.SessionRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class SessionService {

    private final SessionRepository sessionRepository;
    private final ExecutionRepository executionRepository;
    private final ObjectMapper objectMapper;
    private final SessionMapper sessionMapper;

    public SessionService(
            SessionRepository sessionRepository,
            ExecutionRepository executionRepository,
            ObjectMapper objectMapper,
            SessionMapper sessionMapper
    ) {
        this.sessionRepository = sessionRepository;
        this.executionRepository = executionRepository;
        this.objectMapper = objectMapper;
        this.sessionMapper = sessionMapper;
    }

    public SessionResponse createSession(CreateSessionRequest request) {
        Session session = new Session(
            UUID.randomUUID().toString(),
            request.getWorkspaceId() != null ? request.getWorkspaceId() : ApplicationConstants.DEFAULT_WORKSPACE_ID,
            request.getUserId()
        );
        if (request.getVariables() != null) {
            session.setVariables(request.getVariables());
        }
        Session saved = sessionRepository.save(session);
        return SessionResponse.fromEntity(saved);
    }

    public SessionResponse getSession(String sessionId) {
        Session session = getSessionEntity(sessionId);
        return SessionResponse.fromEntity(session);
    }

    public Session getSessionEntity(String sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found: " + sessionId));
    }

    public Session getOrCreateSession(String sessionId, String userId) {
        return sessionRepository.findById(sessionId)
                .map(session -> {
                    if ((session.getUserId() == null || session.getUserId().isBlank() || "anonymous".equalsIgnoreCase(session.getUserId()))
                            && userId != null
                            && !userId.isBlank()) {
                        session.setUserId(userId);
                        session.setLastActivityAt(LocalDateTime.now());
                        return sessionRepository.save(session);
                    }
                    return session;
                })
                .orElseGet(() -> {
                    Session session = new Session(
                            sessionId,
                            ApplicationConstants.DEFAULT_WORKSPACE_ID,
                            userId == null ? "anonymous" : userId
                    );
                    return sessionRepository.save(session);
                });
    }

    public Session updateCurrentExecutionId(Session session, String executionId) {
        session.setCurrentExecutionId(executionId);
        session.setLastActivityAt(LocalDateTime.now());
        return sessionRepository.save(session);
    }

    public Session clearCurrentExecutionId(Session session) {
        session.setCurrentExecutionId(null);
        session.setLastActivityAt(LocalDateTime.now());
        return sessionRepository.save(session);
    }

    public Session pushSuspendedExecution(Session session, Map<String, Object> snapshot) {
        List<Map<String, Object>> stack = getSuspendedExecutions(session);
        stack.add(snapshot);
        session.setSuspendedStack(writeJson(stack));
        session.setLastActivityAt(LocalDateTime.now());
        return sessionRepository.save(session);
    }

    public Optional<Map<String, Object>> peekSuspendedExecution(Session session) {
        List<Map<String, Object>> stack = getSuspendedExecutions(session);
        if (stack.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new LinkedHashMap<>(stack.get(stack.size() - 1)));
    }

    public Optional<Map<String, Object>> popSuspendedExecution(Session session, String executionId) {
        List<Map<String, Object>> stack = getSuspendedExecutions(session);
        for (int index = stack.size() - 1; index >= 0; index--) {
            Map<String, Object> snapshot = stack.get(index);
            if (executionId.equals(String.valueOf(snapshot.get("execution_id")))) {
                Map<String, Object> removed = new LinkedHashMap<>(snapshot);
                stack.remove(index);
                session.setSuspendedStack(writeJson(stack));
                session.setLastActivityAt(LocalDateTime.now());
                sessionRepository.save(session);
                return Optional.of(removed);
            }
        }
        return Optional.empty();
    }

    public List<Map<String, Object>> getSuspendedExecutions(Session session) {
        if (session.getSuspendedStack() == null || session.getSuspendedStack().isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(session.getSuspendedStack(), new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    public SessionResponse updateSession(String sessionId, CreateSessionRequest request) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found: " + sessionId));
        session.setLastActivityAt(LocalDateTime.now());
        if (request.getVariables() != null) {
            session.setVariables(request.getVariables());
        }
        Session saved = sessionRepository.save(session);
        return SessionResponse.fromEntity(saved);
    }

    public List<SessionResponse> getSessionsByUserId(String userId) {
        List<Session> sessions = sessionRepository.findByUserIdOrderByLastActivityAtDesc(userId);
        return sessions.stream()
                .filter(this::shouldIncludeInHistory)
                .map(SessionResponse::fromEntity)
                .collect(Collectors.toList());
    }

    public void closeSession(String sessionId) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found: " + sessionId));
        LocalDateTime now = LocalDateTime.now();
        markSessionDeleted(sessionId, now);
        session.setStatus(SessionStatus.DELETED);
        session.setLastActivityAt(now);
    }

    private boolean shouldIncludeInHistory(Session session) {
        if (session.getStatus() == SessionStatus.DELETED) {
            return false;
        }
        return hasRealUserInteraction(session.getId());
    }

    private boolean hasRealUserInteraction(String sessionId) {
        return executionRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(Execution::getInputVariables)
                .map(this::readUserMessage)
                .anyMatch(message -> message != null && !message.isBlank());
    }

    private String readUserMessage(String inputVariables) {
        if (inputVariables == null || inputVariables.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(inputVariables, new TypeReference<Map<String, Object>>() {});
            return readPreferredText(payload, "user_message", "message", "content", "question");
        } catch (Exception ignored) {
            return null;
        }
    }

    private String readPreferredText(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            Object value = payload.get(key);
            if (value instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return null;
        }
    }


    private void markSessionDeleted(String sessionId, LocalDateTime lastActivityAt) {
        sessionMapper.markSessionStatus(sessionId, SessionStatus.DELETED.name(), lastActivityAt);
    }
}
