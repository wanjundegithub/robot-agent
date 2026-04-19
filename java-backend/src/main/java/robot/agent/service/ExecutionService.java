package robot.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import robot.agent.dto.response.SessionMessageResponse;
import robot.agent.model.Execution;
import robot.agent.model.ExecutionNodeLog;
import robot.agent.model.ExecutionStatus;
import robot.agent.model.Session;
import robot.agent.repository.ExecutionNodeLogRepository;
import robot.agent.repository.ExecutionRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    private final SessionService sessionService;
    private final WorkflowService workflowService;
    private final ExecutionRepository executionRepository;
    private final ExecutionNodeLogRepository executionNodeLogRepository;
    private final PythonClient pythonClient;
    private final WebSocketPublisher webSocketPublisher;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final AccessControlService accessControlService;
    private final ConfirmationService confirmationService;
    private final EntryProtectionService entryProtectionService;

    public ExecutionService(
            SessionService sessionService,
            WorkflowService workflowService,
            ExecutionRepository executionRepository,
            ExecutionNodeLogRepository executionNodeLogRepository,
            PythonClient pythonClient,
            WebSocketPublisher webSocketPublisher,
            AuditService auditService,
            ObjectMapper objectMapper,
            AccessControlService accessControlService,
            ConfirmationService confirmationService,
            EntryProtectionService entryProtectionService
    ) {
        this.sessionService = sessionService;
        this.workflowService = workflowService;
        this.executionRepository = executionRepository;
        this.executionNodeLogRepository = executionNodeLogRepository;
        this.pythonClient = pythonClient;
        this.webSocketPublisher = webSocketPublisher;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.accessControlService = accessControlService;
        this.confirmationService = confirmationService;
        this.entryProtectionService = entryProtectionService;
    }

    @Transactional
    public SendMessageResponse startExecution(String sessionId, SendMessageRequest request) {
        log.info(
                "execution.start.request sessionId={} messageId={} userId={} workflowCode={} workflowVersion={} hasWorkflowDefinition={} contentPreview={}",
                sessionId,
                request == null ? null : request.getMessageId(),
                request == null ? null : request.getUserId(),
                request == null ? null : request.getWorkflowCode(),
                request == null ? null : request.getWorkflowVersion(),
                request != null && request.getWorkflowDefinition() != null && !request.getWorkflowDefinition().isEmpty(),
                preview(request == null ? null : request.getContent())
        );
        Session session = sessionService.getOrCreateSession(sessionId, request.getUserId());
        String effectiveUserId = request.getUserId() == null || request.getUserId().isBlank()
                ? session.getUserId()
                : request.getUserId();
        if (request.getMessageId() != null && !request.getMessageId().isBlank()) {
            Execution existingExecution = executionRepository.findBySessionIdAndClientMessageId(session.getId(), request.getMessageId())
                    .orElse(null);
            if (existingExecution != null) {
                return buildSendMessageResponse(existingExecution, null, null);
            }
        }

        Execution activeExecution = resolveActiveExecution(session);
        boolean explicitWorkflowExecution = request.getWorkflowCode() != null
                && !request.getWorkflowCode().isBlank()
                && request.getWorkflowVersion() != null
                && !request.getWorkflowVersion().isBlank();
        RoutingDecision routingDecision = explicitWorkflowExecution
                ? buildExplicitRoutingDecision(request, activeExecution)
                : workflowService.routeMessage(request.getContent(), activeExecution);
        log.info(
                "execution.route sessionId={} workflowCode={} workflowVersion={} decision={} reason={} confidence={}",
                session.getId(),
                routingDecision.workflowCode(),
                routingDecision.workflowVersion(),
                routingDecision.decision(),
                routingDecision.reason(),
                routingDecision.confidence()
        );
        String requestedToolCode = confirmationService.resolveRequestedToolCode(
                request.getRequestedToolCode(),
                request.getContent()
        );

        if (routingDecision.isSwitchRequired() && !Boolean.TRUE.equals(request.getConfirmSwitch())) {
            return buildRouteDecisionResponse(session, activeExecution, routingDecision);
        }

        AccessControlService.AuthorizationDecision authorizationDecision = accessControlService.evaluateExecutionAccess(
                effectiveUserId,
                session.getWorkspaceId(),
                routingDecision.workflowCode(),
                requestedToolCode,
                session.getUserId()
        );
        if (!authorizationDecision.allowed()) {
            log.warn(
                    "execution.permission.denied sessionId={} workflowCode={} toolCode={} reason={}",
                    session.getId(),
                    routingDecision.workflowCode(),
                    requestedToolCode,
                    authorizationDecision.reason()
            );
            auditService.logAction(
                    session.getWorkspaceId(),
                    effectiveUserId,
                    "execution.permission_denied",
                    "execution",
                    session.getId(),
                    Map.of(
                            "workflow_code", routingDecision.workflowCode(),
                            "requested_tool_code", requestedToolCode == null ? "" : requestedToolCode,
                            "reason", authorizationDecision.reason()
                    ),
                    403
            );
            return buildPermissionDeniedResponse(session, routingDecision, authorizationDecision, requestedToolCode);
        }

        ConfirmationService.ConfirmationEvaluation confirmationEvaluation = confirmationService.evaluate(
                session.getId(),
                effectiveUserId,
                request.getContent(),
                requestedToolCode,
                request.getConfirmationId(),
                Boolean.TRUE.equals(request.getCancelConfirmation())
        );
        if (confirmationEvaluation.cancelled()) {
            log.info(
                    "execution.confirmation.cancelled sessionId={} toolCode={}",
                    session.getId(),
                    confirmationEvaluation.toolCode()
            );
            auditService.logAction(
                    session.getWorkspaceId(),
                    effectiveUserId,
                    "execution.confirmation_cancelled",
                    "execution",
                    session.getId(),
                    confirmationEvaluation.asAuditPayload(),
                    200
            );
            return buildConfirmationCancelledResponse(session, routingDecision, confirmationEvaluation);
        }
        if (confirmationEvaluation.requiresConfirmation()) {
            log.info(
                    "execution.confirmation.required sessionId={} toolCode={} confirmationId={}",
                    session.getId(),
                    confirmationEvaluation.toolCode(),
                    confirmationEvaluation.confirmationId()
            );
            auditService.logAction(
                    session.getWorkspaceId(),
                    effectiveUserId,
                    "execution.confirmation_requested",
                    "execution",
                    session.getId(),
                    confirmationEvaluation.asAuditPayload(),
                    202
            );
            return buildConfirmationRequiredResponse(session, routingDecision, confirmationEvaluation);
        }

        EntryProtectionService.ProtectionDecision protectionDecision = entryProtectionService.evaluateExecutionStart(
                effectiveUserId,
                session.getId(),
                routingDecision.workflowCode(),
                confirmationEvaluation.toolCode()
        );
        if (!protectionDecision.allowed()) {
            log.warn(
                    "execution.protected sessionId={} workflowCode={} status={} reason={}",
                    session.getId(),
                    routingDecision.workflowCode(),
                    protectionDecision.status(),
                    protectionDecision.reason()
            );
            auditService.logAction(
                    session.getWorkspaceId(),
                    effectiveUserId,
                    "execution.protected",
                    "execution",
                    session.getId(),
                    Map.of(
                            "protection_status", protectionDecision.status(),
                            "reason", protectionDecision.reason()
                    ),
                    "rate_limited".equals(protectionDecision.status()) ? 429 : 503
            );
            return buildProtectionResponse(session, routingDecision, protectionDecision, confirmationEvaluation.toolCode());
        }

        if (routingDecision.isSwitchRequired() && activeExecution != null) {
            suspendForSwitch(session, activeExecution, routingDecision);
        }

        ExperimentAssignment experimentAssignment = assignExperiment(session.getId(), routingDecision.workflowCode());
        WorkflowService.RuntimeExecutionBundle runtimeBundle = explicitWorkflowExecution
                ? buildExplicitRuntimeExecutionBundle(routingDecision, request)
                : workflowService.buildRuntimeExecutionBundle(
                        routingDecision.workflowCode(),
                        routingDecision.workflowVersion()
                );

        Execution execution = new Execution();
        execution.setId(UUID.randomUUID().toString());
        execution.setSessionId(session.getId());
        execution.setWorkflowCode(routingDecision.workflowCode());
        execution.setWorkflowVersion(routingDecision.workflowVersion());
        execution.setClientMessageId(request.getMessageId());
        execution.setStatus(ExecutionStatus.PENDING);
        Map<String, Object> inputVariables = new LinkedHashMap<>();
        inputVariables.put("user_message", request.getContent());
        inputVariables.put("user_id", effectiveUserId);
        inputVariables.put("route_decision", routingDecision.decision());
        inputVariables.put("route_confidence", routingDecision.confidence());
        inputVariables.put("route_threshold", routingDecision.threshold());
        inputVariables.put("threshold_source", routingDecision.thresholdSource());
        inputVariables.put("experiment_id", experimentAssignment.experimentId());
        inputVariables.put("experiment_group", experimentAssignment.experimentGroup());
        execution.setInputVariables(writeJson(inputVariables));
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
        executeRequest.setUserId(effectiveUserId);
        executeRequest.setExperimentId(experimentAssignment.experimentId());
        executeRequest.setExperimentGroup(experimentAssignment.experimentGroup());
        executeRequest.setDynamicThreshold(routingDecision.threshold());
        executeRequest.setThresholdSource(routingDecision.thresholdSource());
        executeRequest.setRequestedToolCode(confirmationEvaluation.toolCode());
        executeRequest.setConfirmedToolCodes(
                confirmationEvaluation.toolCode() == null ? List.of() : List.of(confirmationEvaluation.toolCode())
        );
        executeRequest.setWorkflowDefinition(runtimeBundle.workflowDefinition());
        executeRequest.setEntryRule(runtimeBundle.entryRule());
        executeRequest.setWorkflowConfig(runtimeBundle.workflowConfig());
        executeRequest.setWorkflowCatalog(runtimeBundle.workflowCatalog());
        executeRequest.setProviderConfigs(runtimeBundle.providerConfigs());
        executeRequest.setModelProfiles(runtimeBundle.modelProfiles());
        executeRequest.setIntentProfileCode(runtimeBundle.routingProfileCode());
        Map<String, Object> executeInput = new LinkedHashMap<>();
        executeInput.put("user_message", request.getContent());
        executeInput.put("experiment_id", experimentAssignment.experimentId());
        executeInput.put("experiment_group", experimentAssignment.experimentGroup());
        executeInput.put("requested_tool_code", confirmationEvaluation.toolCode());
        executeInput.put("confirmed_tool_codes", executeRequest.getConfirmedToolCodes());
        executeInput.put("intent_profile_code", runtimeBundle.routingProfileCode());
        executeRequest.setInputVariables(executeInput);
        log.info(
                "execution.dispatch executionId={} sessionId={} workflowCode={} workflowVersion={} providerCount={} profileCount={} intentProfileCode={}",
                saved.getId(),
                session.getId(),
                saved.getWorkflowCode(),
                saved.getWorkflowVersion(),
                runtimeBundle.providerConfigs().size(),
                runtimeBundle.modelProfiles().size(),
                runtimeBundle.routingProfileCode()
        );

        Flux<ServerSentEvent<String>> stream = pythonClient.execute(executeRequest);
        stream.subscribe(event -> handleEvent(saved.getId(), event), error -> {
            log.error("execution.python.error executionId={} sessionId={} message={}", saved.getId(), session.getId(), error.getMessage(), error);
            entryProtectionService.recordPythonFailure(error.getMessage());
            Execution failure = executionRepository.findById(saved.getId()).orElse(saved);
            failure.setStatus(ExecutionStatus.FAILED);
            failure.setError(error.getMessage());
            executionRepository.save(failure);
            maybeOfferResume(session.getId(), saved.getId());
        });

        log.info("execution.started executionId={} sessionId={} workflowCode={} workflowVersion={}", saved.getId(), session.getId(), saved.getWorkflowCode(), saved.getWorkflowVersion());
        auditService.logAction(session.getWorkspaceId(), session.getUserId(), "execution.start", "execution", saved.getId(), routingDecision, 200);
        return buildSendMessageResponse(saved, routingDecision, activeExecution, experimentAssignment);
    }

    @Transactional
    public FormSubmitResponse submitForm(String executionId, FormSubmitRequest request) {
        log.info("execution.form.submit executionId={} submitId={}", executionId, request == null ? null : request.getSubmitId());
        try {
            pythonClient.submitForm(executionId, request).block(Duration.ofSeconds(5));
            Execution execution = executionRepository.findById(executionId).orElse(null);
            if (execution != null) {
                execution.setStatus(ExecutionStatus.RUNNING);
                execution.setError(null);
                executionRepository.save(execution);
            }
        } catch (RuntimeException error) {
            log.error("execution.form.submit.failed executionId={} message={}", executionId, error.getMessage(), error);
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

    @Transactional(readOnly = true)
    public List<SessionMessageResponse> getSessionMessageHistory(String sessionId) {
        sessionService.getSessionEntity(sessionId);
        List<Execution> executions = executionRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        List<SessionMessageResponse> messages = new ArrayList<>();

        for (Execution execution : executions) {
            String userMessage = readPreferredText(
                    parseJson(execution.getInputVariables()),
                    "user_message", "message", "content", "question"
            );
            if (userMessage != null && !userMessage.isBlank()) {
                messages.add(buildSessionMessage(
                        execution.getId() + "_user",
                        "user",
                        userMessage,
                        execution.getCreatedAt(),
                        execution.getId()
                ));
            }

            String assistantMessage = extractAssistantMessage(execution);
            if (assistantMessage != null && !assistantMessage.isBlank()) {
                LocalDateTime assistantTime = execution.getCompletedAt() != null ? execution.getCompletedAt() : execution.getCreatedAt();
                messages.add(buildSessionMessage(
                        execution.getId() + "_ai",
                        execution.getStatus() == ExecutionStatus.FAILED ? "error" : "ai",
                        assistantMessage,
                        assistantTime,
                        execution.getId()
                ));
            }
        }

        return messages;
    }

    @Transactional
    public ResumeExecutionResponse resumeExecution(String executionId) {
        log.info("execution.resume.request executionId={}", executionId);
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

        log.debug("execution.event executionId={} sessionId={} eventType={} payloadKeys={}", executionId, sessionId, eventType, payload.keySet());

        switch (eventType) {
            case "routing.decided":
                webSocketPublisher.publishEvent(eventType, executionId, sessionId, payload);
                break;
            case "execution.started":
                entryProtectionService.recordPythonSuccess();
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
            case "cost.recorded":
            case "budget.alert":
            case "replay.snapshot_ready":
            case "confirmation.required":
            case "protection.rate_limited":
            case "protection.degraded":
            case "protection.circuit_open":
            case "optimization.vector_access":
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

    private WorkflowService.RuntimeExecutionBundle buildExplicitRuntimeExecutionBundle(
            RoutingDecision routingDecision,
            SendMessageRequest request
    ) {
        log.info(
                "execution.explicit.binding.use_published workflowCode={} workflowVersion={} inlineDefinitionIgnored={}",
                routingDecision.workflowCode(),
                routingDecision.workflowVersion(),
                request.getWorkflowDefinition() != null && !request.getWorkflowDefinition().isEmpty()
        );
        return workflowService.buildRuntimeExecutionBundle(
                routingDecision.workflowCode(),
                routingDecision.workflowVersion()
        );
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
        response.setRouteThreshold(routingDecision.threshold());
        response.setThresholdSource(routingDecision.thresholdSource());
        response.setRouteReason(routingDecision.reason());
        response.setCandidateWorkflows(routingDecision.candidateWorkflows());
        response.setActiveExecutionId(activeExecution == null ? null : activeExecution.getId());
        response.setPriority(routingDecision.priority());
        return response;
    }

    private SendMessageResponse buildSendMessageResponse(
            Execution execution,
            RoutingDecision routingDecision,
            Execution activeExecution,
            ExperimentAssignment experimentAssignment
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
            response.setRouteThreshold(routingDecision.threshold());
            response.setThresholdSource(routingDecision.thresholdSource());
            response.setRouteReason(routingDecision.reason());
            response.setCandidateWorkflows(routingDecision.candidateWorkflows());
            response.setPriority(routingDecision.priority());
        }
        if (experimentAssignment != null) {
            response.setExperimentId(experimentAssignment.experimentId());
            response.setExperimentGroup(experimentAssignment.experimentGroup());
        }
        response.setActiveExecutionId(activeExecution == null ? null : activeExecution.getId());
        return response;
    }

    private SendMessageResponse buildSendMessageResponse(
            Execution execution,
            RoutingDecision routingDecision,
            Execution activeExecution
    ) {
        return buildSendMessageResponse(execution, routingDecision, activeExecution, null);
    }

    private SendMessageResponse buildPermissionDeniedResponse(
            Session session,
            RoutingDecision routingDecision,
            AccessControlService.AuthorizationDecision authorizationDecision,
            String requestedToolCode
    ) {
        SendMessageResponse response = buildBaseGovernanceResponse(session, routingDecision, "permission_denied", requestedToolCode);
        response.setPermissionEffect(authorizationDecision.effect());
        response.setPermissionReason(authorizationDecision.reason());
        return response;
    }

    private SendMessageResponse buildConfirmationRequiredResponse(
            Session session,
            RoutingDecision routingDecision,
            ConfirmationService.ConfirmationEvaluation confirmationEvaluation
    ) {
        SendMessageResponse response = buildBaseGovernanceResponse(session, routingDecision, "confirmation_required", confirmationEvaluation.toolCode());
        response.setConfirmationId(confirmationEvaluation.confirmationId());
        response.setConfirmationExpiresAt(confirmationEvaluation.confirmationExpiresAt());
        return response;
    }

    private SendMessageResponse buildConfirmationCancelledResponse(
            Session session,
            RoutingDecision routingDecision,
            ConfirmationService.ConfirmationEvaluation confirmationEvaluation
    ) {
        return buildBaseGovernanceResponse(session, routingDecision, "confirmation_cancelled", confirmationEvaluation.toolCode());
    }

    private SendMessageResponse buildProtectionResponse(
            Session session,
            RoutingDecision routingDecision,
            EntryProtectionService.ProtectionDecision protectionDecision,
            String requestedToolCode
    ) {
        SendMessageResponse response = buildBaseGovernanceResponse(session, routingDecision, protectionDecision.status(), requestedToolCode);
        response.setProtectionStatus(protectionDecision.status());
        response.setProtectionReason(protectionDecision.reason());
        response.setRetryAfterSeconds(protectionDecision.retryAfterSeconds());
        response.setDegradationMessage(protectionDecision.degradationMessage());
        return response;
    }

    private SendMessageResponse buildBaseGovernanceResponse(
            Session session,
            RoutingDecision routingDecision,
            String status,
            String requestedToolCode
    ) {
        SendMessageResponse response = new SendMessageResponse();
        response.setSessionId(session.getId());
        response.setStatus(status);
        response.setWorkflowCode(routingDecision.workflowCode());
        response.setWorkflowVersion(routingDecision.workflowVersion());
        response.setRouteDecision(routingDecision.decision());
        response.setRouteConfidence(routingDecision.confidence());
        response.setRouteThreshold(routingDecision.threshold());
        response.setThresholdSource(routingDecision.thresholdSource());
        response.setRouteReason(routingDecision.reason());
        response.setCandidateWorkflows(routingDecision.candidateWorkflows());
        response.setPriority(routingDecision.priority());
        response.setRequestedToolCode(requestedToolCode);
        return response;
    }

    private RoutingDecision buildExplicitRoutingDecision(SendMessageRequest request, Execution activeExecution) {
        String workflowCode = request.getWorkflowCode();
        String workflowVersion = request.getWorkflowVersion();
        workflowService.requirePublishedWorkflowVersion(workflowCode, workflowVersion);
        String decision = "start";
        String reason = "canvas_selected_workflow";
        if (activeExecution != null
                && !activeExecution.getStatus().isTerminal()
                && activeExecution.getStatus() != ExecutionStatus.SUSPENDED
                && !workflowCode.equals(activeExecution.getWorkflowCode())) {
            decision = "switch_required";
            reason = "active_execution_conflict";
        }
        return new RoutingDecision(
                decision,
                workflowCode,
                workflowVersion,
                1.0d,
                0.0d,
                "manual_canvas",
                reason,
                List.of(workflowCode),
                100
        );
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
            JsonNode node = readLenientJson(data);
            return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private JsonNode readLenientJson(String raw) throws Exception {
        String candidate = raw;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                JsonNode node = objectMapper.readTree(candidate);
                if (node.isTextual()) {
                    candidate = node.asText();
                    continue;
                }
                return node;
            } catch (Exception ignored) {
                candidate = candidate.trim();
                if (candidate.startsWith("\"") && candidate.endsWith("\"") && candidate.length() >= 2) {
                    candidate = candidate.substring(1, candidate.length() - 1);
                }
                candidate = candidate.replace("\\\"", "\"").replace("\"\"", "\"");
            }
        }
        return objectMapper.readTree("{}");
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String preview(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80) + "...";
    }

    private ExperimentAssignment assignExperiment(String sessionId, String workflowCode) {
        String experimentId = "phase4-routing-ab";
        int bucket = Math.abs((sessionId + ":" + workflowCode).hashCode()) % 2;
        String group = bucket == 0 ? "A" : "B";
        return new ExperimentAssignment(experimentId, group);
    }

    private record ExperimentAssignment(String experimentId, String experimentGroup) {
    }

    private SessionMessageResponse buildSessionMessage(
            String id,
            String type,
            String content,
            LocalDateTime timestamp,
            String executionId
    ) {
        SessionMessageResponse response = new SessionMessageResponse();
        response.setId(id);
        response.setType(type);
        response.setContent(content);
        response.setTimestamp((timestamp == null ? LocalDateTime.now() : timestamp).toString());
        response.setExecutionId(executionId);
        return response;
    }

    private String extractAssistantMessage(Execution execution) {
        if (execution.getStatus() == ExecutionStatus.FAILED && execution.getError() != null && !execution.getError().isBlank()) {
            return execution.getError();
        }

        Map<String, Object> output = parseJson(execution.getOutputVariables());
        String direct = readPreferredText(output, "answer", "text", "message", "content", "result", "reply");
        if (direct != null) {
            return direct;
        }

        Map<String, Object> variables = parseJson(execution.getVariables());
        String fallback = readPreferredText(variables, "answer", "text", "message", "content", "result", "reply");
        if (fallback != null) {
            return fallback;
        }

        if (!output.isEmpty()) {
            return writeJson(output);
        }
        if (!variables.isEmpty()) {
            return writeJson(variables);
        }
        return null;
    }

    private String readPreferredText(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            String text = flattenValue(value);
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private String flattenValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text.isBlank() ? null : text;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            return writeJson(map);
        }
        if (value instanceof List<?> list && !list.isEmpty()) {
            return writeJson(list);
        }
        return null;
    }
}
