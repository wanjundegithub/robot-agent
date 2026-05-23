package robot.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

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
        log.info(
                "session.create.request userId={} workspaceId={} hasVariables={}",
                request.getUserId(),
                request.getWorkspaceId(),
                request.getVariables() != null && !request.getVariables().isBlank()
        );
        Session session = new Session(
            UUID.randomUUID().toString(),
            request.getWorkspaceId() != null ? request.getWorkspaceId() : ApplicationConstants.DEFAULT_WORKSPACE_ID,
            request.getUserId()
        );
        if (request.getVariables() != null) {
            session.setVariables(request.getVariables());
        }
        Session saved = sessionRepository.save(session);
        log.info(
                "session.create.saved sessionId={} userId={} workspaceId={} status={}",
                saved.getId(),
                saved.getUserId(),
                saved.getWorkspaceId(),
                saved.getStatus()
        );
        return SessionResponse.fromEntity(saved);
    }

    public SessionResponse getSession(String sessionId) {
        Session session = getSessionEntity(sessionId);
        return SessionResponse.fromEntity(session);
    }

    public Session getSessionEntity(String sessionId) {
        log.info("session.lookup sessionId={}", sessionId);
        return sessionRepository.findById(sessionId)
                .map(session -> {
                    log.info(
                            "session.lookup.hit sessionId={} userId={} workspaceId={} status={} currentExecutionId={}",
                            session.getId(),
                            session.getUserId(),
                            session.getWorkspaceId(),
                            session.getStatus(),
                            session.getCurrentExecutionId()
                    );
                    return session;
                })
                .orElseThrow(() -> {
                    log.warn("session.lookup.miss sessionId={}", sessionId);
                    return new RuntimeException("Session not found: " + sessionId);
                });
    }

    public Session getOrCreateSession(String sessionId, String userId) {
        log.info("session.get_or_create.request sessionId={} userId={}", sessionId, userId);
        return sessionRepository.findById(sessionId)
                .map(session -> {
                    log.info(
                            "session.get_or_create.hit sessionId={} storedUserId={} currentExecutionId={} status={}",
                            session.getId(),
                            session.getUserId(),
                            session.getCurrentExecutionId(),
                            session.getStatus()
                    );
                    if ((session.getUserId() == null || session.getUserId().isBlank() || "anonymous".equalsIgnoreCase(session.getUserId()))
                            && userId != null
                            && !userId.isBlank()) {
                        session.setUserId(userId);
                        session.setLastActivityAt(LocalDateTime.now());
                        Session saved = sessionRepository.save(session);
                        log.info("session.get_or_create.user_updated sessionId={} userId={}", saved.getId(), saved.getUserId());
                        return saved;
                    }
                    return session;
                })
                .orElseGet(() -> {
                    Session session = new Session(
                            sessionId,
                            ApplicationConstants.DEFAULT_WORKSPACE_ID,
                            userId == null ? "anonymous" : userId
                    );
                    Session saved = sessionRepository.save(session);
                    log.info("session.get_or_create.created sessionId={} userId={} workspaceId={}", saved.getId(), saved.getUserId(), saved.getWorkspaceId());
                    return saved;
                });
    }

    public Session updateCurrentExecutionId(Session session, String executionId) {
        String previousExecutionId = session.getCurrentExecutionId();
        session.setCurrentExecutionId(executionId);
        session.setLastActivityAt(LocalDateTime.now());
        Session saved = sessionRepository.save(session);
        log.info("session.current_execution.updated sessionId={} fromExecutionId={} toExecutionId={}", saved.getId(), previousExecutionId, executionId);
        return saved;
    }

    public Session clearCurrentExecutionId(Session session) {
        String previousExecutionId = session.getCurrentExecutionId();
        session.setCurrentExecutionId(null);
        session.setLastActivityAt(LocalDateTime.now());
        Session saved = sessionRepository.save(session);
        log.info("session.current_execution.cleared sessionId={} previousExecutionId={}", saved.getId(), previousExecutionId);
        return saved;
    }

    public Session pushSuspendedExecution(Session session, Map<String, Object> snapshot) {
        List<Map<String, Object>> stack = getSuspendedExecutions(session);
        stack.add(snapshot);
        session.setSuspendedStack(writeJson(stack));
        session.setLastActivityAt(LocalDateTime.now());
        Session saved = sessionRepository.save(session);
        log.info(
                "session.suspended_stack.pushed sessionId={} executionId={} stackSize={}",
                saved.getId(),
                snapshot == null ? null : snapshot.get("execution_id"),
                stack.size()
        );
        return saved;
    }

    public Optional<Map<String, Object>> peekSuspendedExecution(Session session) {
        List<Map<String, Object>> stack = getSuspendedExecutions(session);
        if (stack.isEmpty()) {
            log.info("session.suspended_stack.peek_empty sessionId={}", session.getId());
            return Optional.empty();
        }
        Map<String, Object> snapshot = new LinkedHashMap<>(stack.get(stack.size() - 1));
        log.info("session.suspended_stack.peek sessionId={} executionId={} stackSize={}", session.getId(), snapshot.get("execution_id"), stack.size());
        return Optional.of(snapshot);
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
                log.info("session.suspended_stack.popped sessionId={} executionId={} remainingStackSize={}", session.getId(), executionId, stack.size());
                return Optional.of(removed);
            }
        }
        log.info("session.suspended_stack.pop_miss sessionId={} executionId={} stackSize={}", session.getId(), executionId, stack.size());
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
