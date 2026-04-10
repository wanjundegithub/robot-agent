package robot.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import robot.agent.dto.request.CreateWorkflowVersionRequest;
import robot.agent.dto.response.WorkflowResponse;
import robot.agent.dto.response.WorkflowVersionResponse;
import robot.agent.model.Workflow;
import robot.agent.model.Execution;
import robot.agent.model.ExecutionStatus;
import robot.agent.model.WorkflowStatus;
import robot.agent.model.WorkflowVersion;
import robot.agent.model.WorkflowVersionStatus;
import robot.agent.repository.WorkflowRepository;
import robot.agent.repository.WorkflowVersionRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class WorkflowService {

    private final WorkflowRepository workflowRepository;
    private final WorkflowVersionRepository workflowVersionRepository;
    private final ObjectMapper objectMapper;
    private final AccessControlService accessControlService;
    private final AuditService auditService;

    public WorkflowService(
            WorkflowRepository workflowRepository,
            WorkflowVersionRepository workflowVersionRepository,
            ObjectMapper objectMapper,
            AccessControlService accessControlService,
            AuditService auditService
    ) {
        this.workflowRepository = workflowRepository;
        this.workflowVersionRepository = workflowVersionRepository;
        this.objectMapper = objectMapper;
        this.accessControlService = accessControlService;
        this.auditService = auditService;
    }

    public WorkflowResponse createWorkflow(String userId, String workflowCode, String name, String description, Long workspaceId) {
        Long effectiveWorkspaceId = workspaceId != null ? workspaceId : 1L;
        accessControlService.requireAnyRole(userId, effectiveWorkspaceId, Set.of("workflow_admin"));

        Workflow workflow = new Workflow();
        workflow.setWorkflowCode(workflowCode);
        workflow.setName(name);
        workflow.setDescription(description);
        workflow.setWorkspaceId(effectiveWorkspaceId);
        workflow.setStatus(WorkflowStatus.DRAFT);
        workflow.setCreatedBy(userId);
        workflow.setCreatedAt(LocalDateTime.now());
        workflow.setUpdatedAt(LocalDateTime.now());

        Workflow saved = workflowRepository.save(workflow);
        auditService.logAction(effectiveWorkspaceId, userId, "workflow.create", "workflow_definition", workflowCode, null, 200);
        return WorkflowResponse.fromEntity(saved);
    }

    public List<WorkflowResponse> getAllWorkflows() {
        List<Workflow> workflows = workflowRepository.findAll();
        return workflows.stream()
                .map(WorkflowResponse::fromEntity)
                .collect(Collectors.toList());
    }

    public List<WorkflowResponse> getPublishedWorkflows() {
        List<Workflow> workflows = workflowRepository.findByStatusOrderByCreatedAtDesc(WorkflowStatus.PUBLISHED);
        return workflows.stream()
                .map(WorkflowResponse::fromEntity)
                .collect(Collectors.toList());
    }

    public WorkflowResponse getWorkflowByCode(String workflowCode) {
        Workflow workflow = workflowRepository.findByWorkflowCode(workflowCode)
                .orElseThrow(() -> new RuntimeException("Workflow not found: " + workflowCode));
        return WorkflowResponse.fromEntity(workflow);
    }

    public WorkflowResponse publishWorkflow(String userId, String workflowCode, String version) {
        Workflow workflow = workflowRepository.findByWorkflowCode(workflowCode)
                .orElseThrow(() -> new RuntimeException("Workflow not found: " + workflowCode));
        accessControlService.requireAnyRole(userId, workflow.getWorkspaceId(), Set.of("workflow_admin"));
        WorkflowVersion workflowVersion = workflowVersionRepository.findByWorkflowCodeAndVersion(workflowCode, version)
                .orElseThrow(() -> new RuntimeException("Workflow version not found: " + workflowCode + "@" + version));

        workflowVersion.setStatus(WorkflowVersionStatus.PUBLISHED);
        workflowVersion.setPublishedAt(LocalDateTime.now());
        workflowVersionRepository.save(workflowVersion);

        workflow.setStatus(WorkflowStatus.PUBLISHED);
        workflow.setCurrentVersion(version);
        workflow.setUpdatedAt(LocalDateTime.now());
        Workflow saved = workflowRepository.save(workflow);
        auditService.logAction(workflow.getWorkspaceId(), userId, "workflow.publish", "workflow_definition", workflowCode, version, 200);

        return WorkflowResponse.fromEntity(saved);
    }

    public WorkflowResponse rollbackWorkflow(String userId, String workflowCode, String version) {
        Workflow workflow = workflowRepository.findByWorkflowCode(workflowCode)
                .orElseThrow(() -> new RuntimeException("Workflow not found: " + workflowCode));
        accessControlService.requireAnyRole(userId, workflow.getWorkspaceId(), Set.of("workflow_admin"));
        WorkflowVersion workflowVersion = workflowVersionRepository.findByWorkflowCodeAndVersion(workflowCode, version)
                .orElseThrow(() -> new RuntimeException("Workflow version not found: " + workflowCode + "@" + version));

        if (workflowVersion.getStatus() != WorkflowVersionStatus.PUBLISHED) {
            workflowVersion.setStatus(WorkflowVersionStatus.PUBLISHED);
            workflowVersion.setPublishedAt(LocalDateTime.now());
            workflowVersionRepository.save(workflowVersion);
        }

        workflow.setCurrentVersion(version);
        workflow.setStatus(WorkflowStatus.PUBLISHED);
        workflow.setUpdatedAt(LocalDateTime.now());
        Workflow saved = workflowRepository.save(workflow);
        auditService.logAction(workflow.getWorkspaceId(), userId, "workflow.rollback", "workflow_definition", workflowCode, version, 200);
        return WorkflowResponse.fromEntity(saved);
    }

    public WorkflowVersionResponse createWorkflowVersion(String userId, String workflowCode, CreateWorkflowVersionRequest request) {
        Workflow workflow = workflowRepository.findByWorkflowCode(workflowCode)
                .orElseThrow(() -> new RuntimeException("Workflow not found: " + workflowCode));
        accessControlService.requireAnyRole(userId, workflow.getWorkspaceId(), Set.of("workflow_admin"));

        WorkflowVersion version = new WorkflowVersion();
        version.setWorkflowCode(workflowCode);
        version.setVersion(request.getVersion());
        version.setDefinition(request.getDefinition());
        version.setEntryRule(request.getEntryRule());
        version.setConfig(request.getConfig());
        version.setStatus(WorkflowVersionStatus.DRAFT);
        version.setCreatedBy(userId);
        version.setCreatedAt(LocalDateTime.now());
        WorkflowVersion saved = workflowVersionRepository.save(version);
        auditService.logAction(workflow.getWorkspaceId(), userId, "workflow.version.create", "workflow_version", workflowCode + ":" + request.getVersion(), request, 200);
        return WorkflowVersionResponse.fromEntity(saved);
    }

    public WorkflowVersionResponse getWorkflowVersion(String workflowCode, String version) {
        WorkflowVersion workflowVersion = workflowVersionRepository.findByWorkflowCodeAndVersion(workflowCode, version)
                .orElseThrow(() -> new RuntimeException("Workflow version not found"));
        return WorkflowVersionResponse.fromEntity(workflowVersion);
    }

    public List<WorkflowVersionResponse> getWorkflowVersions(String workflowCode) {
        List<WorkflowVersion> versions = workflowVersionRepository.findByWorkflowCodeOrderByCreatedAtDesc(workflowCode);
        return versions.stream()
                .map(WorkflowVersionResponse::fromEntity)
                .collect(Collectors.toList());
    }

    public WorkflowVersion selectWorkflowVersionForMessage(String content) {
        RoutingDecision decision = routeMessage(content, null);
        return workflowVersionRepository.findByWorkflowCodeAndVersion(decision.workflowCode(), decision.workflowVersion())
                .orElseThrow(() -> new RuntimeException("Workflow version not found: " + decision.workflowCode() + "@" + decision.workflowVersion()));
    }

    public RoutingDecision routeMessage(String content, Execution activeExecution) {
        List<WorkflowVersion> versions = resolveCurrentWorkflowVersions();
        if (versions.isEmpty()) {
            versions = workflowVersionRepository.findByStatusOrderByCreatedAtDesc(WorkflowVersionStatus.DRAFT);
        }
        if (versions.isEmpty()) {
            throw new RuntimeException("No workflow versions available");
        }

        String normalizedContent = content == null ? "" : content.toLowerCase(Locale.ROOT);
        ModelIntent modelIntent = inferIntent(normalizedContent);
        List<WorkflowScore> scoredVersions = versions.stream()
                .map(version -> new WorkflowScore(
                        version,
                        scoreEntryRule(version.getEntryRule(), normalizedContent),
                        scoreModelMatch(version, modelIntent),
                        extractPriority(version.getEntryRule())
                ))
                .sorted(Comparator.comparingInt(WorkflowScore::totalScore).reversed())
                .toList();

        WorkflowScore best = scoredVersions.get(0);
        List<String> candidates = scoredVersions.stream()
                .limit(3)
                .map(score -> score.version().getWorkflowCode())
                .distinct()
                .toList();

        String decision = "start";
        String reason = best.entryRuleScore() > 0
                ? "entry_rule_and_model"
                : (modelIntent.workflowCode().equals(best.version().getWorkflowCode()) ? "model_fallback" : "default_fallback");
        double confidence = best.totalScore() > 0 ? modelIntent.confidence() : 0.55d;

        if (best.entryRuleScore() == 0 && modelIntent.confidence() < 0.6d) {
            WorkflowVersion fallbackVersion = versions.stream()
                    .filter(version -> "general_query".equals(version.getWorkflowCode()))
                    .findFirst()
                    .orElse(best.version());
            best = new WorkflowScore(fallbackVersion, 0, 0, extractPriority(fallbackVersion.getEntryRule()));
            decision = "fallback";
            reason = "low_confidence_fallback";
            confidence = Math.max(modelIntent.confidence(), 0.55d);
        }

        if (activeExecution != null
                && !activeExecution.getStatus().isTerminal()
                && activeExecution.getStatus() != ExecutionStatus.SUSPENDED
                && !activeExecution.getWorkflowCode().equals(best.version().getWorkflowCode())
                && confidence >= 0.6d) {
            decision = "switch_required";
            reason = "active_execution_conflict";
        }

        return new RoutingDecision(
                decision,
                best.version().getWorkflowCode(),
                best.version().getVersion(),
                confidence,
                reason,
                candidates,
                best.priority()
        );
    }

    private List<WorkflowVersion> resolveCurrentWorkflowVersions() {
        List<Workflow> workflows = workflowRepository.findByStatusOrderByCreatedAtDesc(WorkflowStatus.PUBLISHED);
        List<WorkflowVersion> currentVersions = new ArrayList<>();
        for (Workflow workflow : workflows) {
            if (workflow.getCurrentVersion() == null || workflow.getCurrentVersion().isBlank()) {
                continue;
            }
            workflowVersionRepository.findByWorkflowCodeAndVersion(workflow.getWorkflowCode(), workflow.getCurrentVersion())
                    .ifPresent(currentVersions::add);
        }
        return currentVersions;
    }

    private int scoreEntryRule(String entryRuleJson, String normalizedContent) {
        if (entryRuleJson == null || entryRuleJson.isBlank() || normalizedContent.isBlank()) {
            return 0;
        }

        try {
            JsonNode entryRule = readJsonObject(entryRuleJson);
            int priority = Math.max(entryRule.path("priority").asInt(0), 0);
            int keywordHits = countMatches(entryRule.path("keywords"), normalizedContent);
            int intentHits = countMatches(entryRule.path("intent_codes"), normalizedContent);
            int totalHits = keywordHits + intentHits;
            if (totalHits == 0) {
                return 0;
            }
            return priority * 100 + totalHits;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private int scoreModelMatch(WorkflowVersion version, ModelIntent modelIntent) {
        if (modelIntent.workflowCode().equals(version.getWorkflowCode())) {
            return (int) Math.round(modelIntent.confidence() * 100);
        }
        try {
            JsonNode entryRule = readJsonObject(version.getEntryRule());
            JsonNode intentCodes = entryRule.path("intent_codes");
            if (intentCodes.isArray()) {
                for (JsonNode intentCode : intentCodes) {
                    if (modelIntent.intentCode().equalsIgnoreCase(intentCode.asText())) {
                        return (int) Math.round(modelIntent.confidence() * 80);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private int extractPriority(String entryRuleJson) {
        if (entryRuleJson == null || entryRuleJson.isBlank()) {
            return 0;
        }
        try {
            return Math.max(readJsonObject(entryRuleJson).path("priority").asInt(0), 0);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private ModelIntent inferIntent(String normalizedContent) {
        if (containsAny(normalizedContent, "航班", "机票", "flight", "ticket")) {
            return new ModelIntent("book_flight", "flight_booking", 0.93d);
        }
        if (containsAny(normalizedContent, "酒店", "住宿", "hotel")) {
            return new ModelIntent("book_hotel", "hotel_booking", 0.90d);
        }
        if (containsAny(normalizedContent, "退票", "改签", "政策", "规则", "policy", "refund")) {
            return new ModelIntent("general_query", "general_query", 0.82d);
        }
        return new ModelIntent("general_query", "general_query", 0.55d);
    }

    private boolean containsAny(String normalizedContent, String... keywords) {
        for (String keyword : keywords) {
            if (normalizedContent.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private int countMatches(JsonNode values, String normalizedContent) {
        if (!values.isArray()) {
            return 0;
        }

        int matches = 0;
        for (JsonNode value : values) {
            String candidate = value.asText("").trim().toLowerCase(Locale.ROOT);
            if (!candidate.isEmpty() && normalizedContent.contains(candidate)) {
                matches++;
            }
        }
        return matches;
    }

    private JsonNode readJsonObject(String json) throws Exception {
        JsonNode node = objectMapper.readTree(json);
        if (node.isTextual()) {
            return objectMapper.readTree(node.asText());
        }
        return node;
    }

    private record ModelIntent(String intentCode, String workflowCode, double confidence) {
    }

    private record WorkflowScore(WorkflowVersion version, int entryRuleScore, int modelScore, int priority) {
        int totalScore() {
            return entryRuleScore + modelScore;
        }
    }
}
