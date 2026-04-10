package robot.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import robot.agent.dto.request.ExecuteRequest;
import robot.agent.dto.request.FormSubmitRequest;
import robot.agent.dto.request.SendMessageRequest;
import robot.agent.dto.response.ExecutionResponse;
import robot.agent.dto.response.FormSubmitResponse;
import robot.agent.dto.response.ResumeExecutionResponse;
import robot.agent.dto.response.SendMessageResponse;
import robot.agent.model.Execution;
import robot.agent.model.ExecutionNodeLog;
import robot.agent.model.ExecutionStatus;
import robot.agent.model.Session;
import robot.agent.repository.ExecutionNodeLogRepository;
import robot.agent.repository.ExecutionRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ExecutionService {

    private final SessionService sessionService;
    private final WorkflowService workflowService;
    private final ExecutionRepository executionRepository;
    private final ExecutionNodeLogRepository executionNodeLogRepository;
    private final PythonClient pythonClient;
    private final WebSocketPublisher webSocketPublisher;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public ExecutionService(
            SessionService sessionService,
            WorkflowService workflowService,
            ExecutionRepository executionRepository,
            ExecutionNodeLogRepository executionNodeLogRepository,
            PythonClient pythonClient,
            WebSocketPublisher webSocketPublisher,
            AuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.sessionService = sessionService;
        this.workflowService = workflowService;
        this.executionRepository = executionRepository;
        this.executionNodeLogRepository = executionNodeLogRepository;
        this.pythonClient = pythonClient;
        this.webSocketPublisher = webSocketPublisher;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public SendMessageResponse startExecution(String sessionId, SendMessageRequest request) {
        Session session = sessionService.getOrCreateSession(sessionId, request.getUserId());
        if (request.getMessageId() != null && !request.getMessageId().isBlank()) {
            Execution existingExecution = executionRepository.findBySessionIdAndClientMessageId(session.getId(), request.getMessageId())
                    .orElse(null);
            if (existingExecution != null) {
                return buildSendMessageResponse(existingExecution, null, null);
            }
        }

        Execution activeExecution = resolveActiveExecution(session);
        RoutingDecision routingDecision = workflowService.routeMessage(request.getContent(), activeExecution);

        if (routingDecision.isSwitchRequired() && !Boolean.TRUE.equals(request.getConfirmSwitch())) {
            return buildRouteDecisionResponse(session, activeExecution, routingDecision);
        }

        if (routingDecision.isSwitchRequired() && activeExecution != null) {
            suspendForSwitch(session, activeExecution, routingDecision);
        }

        Execution execution = new Execution();
        execution.setId(UUID.randomUUID().toString());
        execution.setSessionId(session.getId());
        execution.setWorkflowCode(routingDecision.workflowCode());
        execution.setWorkflowVersion(routingDecision.workflowVersion());
        execution.setClientMessageId(request.getMessageId());
        execution.setStatus(ExecutionStatus.PENDING);
        execution.setInputVariables(writeJson(Map.of(
                "user_message", request.getContent(),
                "route_decision", routingDecision.decision(),
                "route_confidence", routingDecision.confidence()
        )));
        execution.setCreatedAt(LocalDateTime.now());
        Execution saved = executionRepository.save(execution);

        sessionService.updateCurrentExecutionId(session, saved.getId());

        ExecuteRequest executeRequest = new ExecuteRequest();
        executeRequest.setSessionId(session.getId());
        executeRequest.setExecutionId(saved.getId());
        executeRequest.setWorkflowCode(saved.getWorkflowCode());
        executeRequest.setWorkflowVersion(saved.getWorkflowVersion());
        executeRequest.setMessageId(request.getMessageId());
        executeRequest.setPriority(routingDecision.priority());
        executeRequest.setRouteDecision(routingDecision.decision());
        executeRequest.setRouteReason(routingDecision.reason());
        executeRequest.setRouteConfidence(routingDecision.confidence());
        executeRequest.setInputVariables(Map.of("user_message", request.getContent()));

        Flux<ServerSentEvent<String>> stream = pythonClient.execute(executeRequest);
        stream.subscribe(event -> handleEvent(saved.getId(), event), error -> {
            Execution failure = executionRepository.findById(saved.getId()).orElse(saved);
            failure.setStatus(ExecutionStatus.FAILED);
            failure.setError(error.getMessage());
            executionRepository.save(failure);
            maybeOfferResume(session.getId(), saved.getId());
        });

        auditService.logAction(session.getWorkspaceId(), session.getUserId(), "execution.start", "execution", saved.getId(), routingDecision, 200);
        return buildSendMessageResponse(saved, routingDecision, activeExecution);
    }

    @Transactional
    public FormSubmitResponse submitForm(String executionId, FormSubmitRequest request) {
        try {
            pythonClient.submitForm(executionId, request).block(Duration.ofSeconds(5));
            Execution execution = executionRepository.findById(executionId).orElse(null);
            if (execution != null) {
                execution.setStatus(ExecutionStatus.RUNNING);
                execution.setError(null);
                executionRepository.save(execution);
            }
        } catch (RuntimeException error) {
            Execution failure = executionRepository.findById(executionId).orElse(null);
            if (failure != null) {
                failure.setStatus(ExecutionStatus.FAILED);
                failure.setError(error.getMessage());
                executionRepository.save(failure);
            }
            throw error;
        }

        FormSubmitResponse response = new FormSubmitResponse();
        response.setExecutionId(executionId);
        response.setStatus("running");
        return response;
    }

    @Transactional
    public ResumeExecutionResponse resumeExecution(String executionId) {
        Execution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new RuntimeException("Execution not found: " + executionId));
        Session session = sessionService.getSessionEntity(execution.getSessionId());
        sessionService.popSuspendedExecution(session, executionId);

        Map<String, Object> resumePayload = pythonClient.resumeExecution(executionId)
                .block(Duration.ofSeconds(5));
        String statusValue = resumePayload == null ? "running" : String.valueOf(resumePayload.getOrDefault("status", "running"));
        ExecutionStatus status = toExecutionStatus(statusValue);
        execution.setStatus(status);
        execution.setError(null);
        executionRepository.save(execution);
        sessionService.updateCurrentExecutionId(session, executionId);

        Map<String, Object> resumeEvent = new LinkedHashMap<>();
        resumeEvent.put("status", status.getValue());
        resumeEvent.put("resume_type", "manual_resume");
        webSocketPublisher.publishEvent("execution.resumed", executionId, session.getId(), resumeEvent);

        ResumeExecutionResponse response = new ResumeExecutionResponse();
        response.setExecutionId(executionId);
        response.setStatus(status.getValue());
        if (resumePayload != null && resumePayload.get("form_definition") != null) {
            response.setFormDefinition(writeJson(resumePayload.get("form_definition")));
            Map<String, Object> formEvent = new LinkedHashMap<>();
            formEvent.put("node_id", execution.getCurrentNodeId());
            formEvent.put("form_definition", resumePayload.get("form_definition"));
            webSocketPublisher.publishEvent("form.requested", executionId, session.getId(), formEvent);
        }

        auditService.logAction(session.getWorkspaceId(), session.getUserId(), "execution.resume", "execution", executionId, resumePayload, 200);
        return response;
    }

    @Transactional(readOnly = true)
    public ExecutionResponse getExecution(String executionId) {
        Execution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new RuntimeException("Execution not found: " + executionId));
        return ExecutionResponse.fromEntity(execution);
    }

    @Transactional(readOnly = true)
    public List<ExecutionResponse> getExecutionsBySession(String sessionId) {
        return executionRepository.findBySessionIdOrderByCreatedAtDesc(sessionId).stream()
                .map(ExecutionResponse::fromEntity)
                .toList();
    }

    private void handleEvent(String executionId, ServerSentEvent<String> event) {
        String eventType = event.event();
        String data = event.data();
        Map<String, Object> payload = parseJson(data);
        Execution execution = executionRepository.findById(executionId).orElse(null);
        String sessionId = execution == null ? stringValue(payload.get("session_id")) : execution.getSessionId();

        if (eventType == null) {
            return;
        }

        switch (eventType) {
            case "routing.decided":
                webSocketPublisher.publishEvent(eventType, executionId, sessionId, payload);
                break;
            case "execution.started":
                updateExecutionStatus(executionId, ExecutionStatus.RUNNING, payload, null);
                webSocketPublisher.publishEvent(eventType, executionId, sessionId, payload);
                break;
            case "execution.waiting_user":
                updateExecutionStatus(executionId, ExecutionStatus.WAITING_USER, payload, null);
                webSocketPublisher.publishEvent(eventType, executionId, sessionId, payload);
                break;
            case "execution.waiting_tool":
                updateExecutionStatus(executionId, ExecutionStatus.WAITING_TOOL, payload, null);
                webSocketPublisher.publishEvent(eventType, executionId, sessionId, payload);
                break;
            case "execution.resumed":
                updateExecutionStatus(executionId, ExecutionStatus.RUNNING, payload, null);
                webSocketPublisher.publishEvent(eventType, executionId, sessionId, payload);
                break;
            case "execution.completed":
                updateExecutionStatus(executionId, ExecutionStatus.COMPLETED, payload, null);
                webSocketPublisher.publishEvent(eventType, executionId, sessionId, payload);
                maybeOfferResume(sessionId, executionId);
                break;
            case "execution.failed":
                updateExecutionStatus(executionId, ExecutionStatus.FAILED, payload, payload.get("error"));
                webSocketPublisher.publishEvent(eventType, executionId, sessionId, payload);
                maybeOfferResume(sessionId, executionId);
                break;
            case "execution.suspended":
                updateExecutionStatus(executionId, ExecutionStatus.SUSPENDED, payload, null);
                webSocketPublisher.publishEvent(eventType, executionId, sessionId, payload);
                break;
            case "node.started":
            case "node.completed":
            case "node.failed":
                updateExecutionNode(executionId, eventType, payload);
                webSocketPublisher.publishEvent(eventType, executionId, sessionId, payload);
                break;
            case "tool.called":
            case "tool.returned":
            case "form.requested":
            case "security.prompt_sanitized":
            case "security.output_rejected":
                webSocketPublisher.publishEvent(eventType, executionId, sessionId, payload);
                break;
            case "message.delta":
                String content = payload.getOrDefault("content", "").toString();
                Boolean isComplete = payload.containsKey("is_complete") ? Boolean.valueOf(payload.get("is_complete").toString()) : null;
                webSocketPublisher.publishMessageDelta(executionId, sessionId, content, isComplete);
                break;
            default:
                webSocketPublisher.publishEvent(eventType, executionId, sessionId, payload);
        }
    }

    private void updateExecutionStatus(String executionId, ExecutionStatus status, Map<String, Object> payload, Object error) {
        Execution execution = executionRepository.findById(executionId).orElse(null);
        if (execution == null) {
            return;
        }
        execution.setStatus(status);
        if (status == ExecutionStatus.RUNNING && execution.getStartedAt() == null) {
            execution.setStartedAt(LocalDateTime.now());
        }
        if (status.isTerminal()) {
            execution.setCompletedAt(LocalDateTime.now());
        }
        if (error != null) {
            execution.setError(String.valueOf(error));
        }
        if (payload.get("output") != null) {
            execution.setOutputVariables(writeJson(payload.get("output")));
        }
        if (payload.get("variables") != null) {
            execution.setVariables(writeJson(payload.get("variables")));
        }
        if (payload.get("metrics") != null) {
            execution.setMetrics(writeJson(payload.get("metrics")));
        }
        executionRepository.save(execution);
    }

    private void updateExecutionNode(String executionId, String eventType, Map<String, Object> payload) {
        Execution execution = executionRepository.findById(executionId).orElse(null);
        if (execution != null && payload.get("node_id") != null) {
            execution.setCurrentNodeId(payload.get("node_id").toString());
            if ("node.completed".equals(eventType) && payload.get("output") instanceof Map<?, ?> outputMap) {
                execution.setVariables(writeJson(mergeVariables(execution.getVariables(), outputMap)));
            }
            executionRepository.save(execution);
        }

        ExecutionNodeLog log = new ExecutionNodeLog();
        log.setExecutionId(executionId);
        log.setNodeId(String.valueOf(payload.getOrDefault("node_id", "")));
        log.setNodeType(String.valueOf(payload.getOrDefault("node_type", "")));
        if ("node.started".equals(eventType)) {
            log.setStatus("running");
            log.setStartedAt(LocalDateTime.now());
        } else if ("node.completed".equals(eventType)) {
            log.setStatus("completed");
            log.setOutput(writeJson(payload.get("output")));
            log.setMetrics(writeJson(payload.get("metrics")));
            log.setCompletedAt(LocalDateTime.now());
        } else if ("node.failed".equals(eventType)) {
            log.setStatus("failed");
            log.setError(payload.getOrDefault("error", "").toString());
            log.setCompletedAt(LocalDateTime.now());
        }
        log.setInput(writeJson(payload.get("input")));
        executionNodeLogRepository.save(log);
    }

    private Execution resolveActiveExecution(Session session) {
        if (session.getCurrentExecutionId() != null && !session.getCurrentExecutionId().isBlank()) {
            Execution currentExecution = executionRepository.findById(session.getCurrentExecutionId()).orElse(null);
            if (currentExecution != null && !currentExecution.getStatus().isTerminal() && currentExecution.getStatus() != ExecutionStatus.SUSPENDED) {
                return currentExecution;
            }
        }
        return executionRepository.findBySessionIdOrderByCreatedAtDesc(session.getId()).stream()
                .filter(execution -> !execution.getStatus().isTerminal() && execution.getStatus() != ExecutionStatus.SUSPENDED)
                .findFirst()
                .orElse(null);
    }

    private void suspendForSwitch(Session session, Execution activeExecution, RoutingDecision routingDecision) {
        if (activeExecution.getStatus() == ExecutionStatus.RUNNING || activeExecution.getStatus() == ExecutionStatus.WAITING_TOOL) {
            pythonClient.suspendExecution(activeExecution.getId(), "switch_to_" + routingDecision.workflowCode())
                    .block(Duration.ofSeconds(5));
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("execution_id", activeExecution.getId());
        snapshot.put("workflow_code", activeExecution.getWorkflowCode());
        snapshot.put("workflow_version", activeExecution.getWorkflowVersion());
        snapshot.put("current_node_id", activeExecution.getCurrentNodeId());
        snapshot.put("state", activeExecution.getStatus().getValue());
        snapshot.put("suspended_at", LocalDateTime.now().toString());
        snapshot.put("reason", "switch_required");

        activeExecution.setStatus(ExecutionStatus.SUSPENDED);
        executionRepository.save(activeExecution);
        sessionService.pushSuspendedExecution(session, snapshot);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("target_workflow_code", routingDecision.workflowCode());
        event.put("target_workflow_version", routingDecision.workflowVersion());
        event.put("reason", routingDecision.reason());
        webSocketPublisher.publishEvent("execution.switch_requested", activeExecution.getId(), session.getId(), event);
        auditService.logAction(session.getWorkspaceId(), session.getUserId(), "execution.switch", "execution", activeExecution.getId(), snapshot, 200);
    }

    private void maybeOfferResume(String sessionId, String completedExecutionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        Session session = sessionService.getSessionEntity(sessionId);
        if (completedExecutionId.equals(session.getCurrentExecutionId())) {
            sessionService.clearCurrentExecutionId(session);
        }
        sessionService.peekSuspendedExecution(session).ifPresent(snapshot -> {
            Map<String, Object> event = new LinkedHashMap<>(snapshot);
            event.put("offered_after_execution_id", completedExecutionId);
            webSocketPublisher.publishEvent(
                    "execution.resume_offered",
                    String.valueOf(snapshot.get("execution_id")),
                    sessionId,
                    event
            );
        });
    }

    private Map<String, Object> mergeVariables(String existingJson, Map<?, ?> outputMap) {
        Map<String, Object> merged = parseJson(existingJson);
        for (Map.Entry<?, ?> entry : outputMap.entrySet()) {
            if (entry.getKey() != null) {
                merged.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return new LinkedHashMap<>(merged);
    }

    private SendMessageResponse buildRouteDecisionResponse(Session session, Execution activeExecution, RoutingDecision routingDecision) {
        SendMessageResponse response = new SendMessageResponse();
        response.setSessionId(session.getId());
        response.setExecutionId(null);
        response.setWorkflowCode(routingDecision.workflowCode());
        response.setWorkflowVersion(routingDecision.workflowVersion());
        response.setStatus(routingDecision.decision());
        response.setRouteDecision(routingDecision.decision());
        response.setRouteConfidence(routingDecision.confidence());
        response.setRouteReason(routingDecision.reason());
        response.setCandidateWorkflows(routingDecision.candidateWorkflows());
        response.setActiveExecutionId(activeExecution == null ? null : activeExecution.getId());
        response.setPriority(routingDecision.priority());
        return response;
    }

    private SendMessageResponse buildSendMessageResponse(
            Execution execution,
            RoutingDecision routingDecision,
            Execution activeExecution
    ) {
        SendMessageResponse response = new SendMessageResponse();
        response.setSessionId(execution.getSessionId());
        response.setExecutionId(execution.getId());
        response.setWorkflowCode(execution.getWorkflowCode());
        response.setWorkflowVersion(execution.getWorkflowVersion());
        if (execution.getStatus() == null || execution.getStatus() == ExecutionStatus.PENDING) {
            response.setStatus("running");
        } else {
            response.setStatus(execution.getStatus().getValue());
        }
        if (routingDecision != null) {
            response.setRouteDecision(routingDecision.decision());
            response.setRouteConfidence(routingDecision.confidence());
            response.setRouteReason(routingDecision.reason());
            response.setCandidateWorkflows(routingDecision.candidateWorkflows());
            response.setPriority(routingDecision.priority());
        }
        response.setActiveExecutionId(activeExecution == null ? null : activeExecution.getId());
        return response;
    }

    private ExecutionStatus toExecutionStatus(String statusValue) {
        for (ExecutionStatus status : ExecutionStatus.values()) {
            if (status.getValue().equalsIgnoreCase(statusValue)) {
                return status;
            }
        }
        return ExecutionStatus.RUNNING;
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> parseJson(String data) {
        if (data == null || data.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(data, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
