package robot.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import robot.agent.dto.request.CreateWorkflowVersionRequest;
import robot.agent.dto.response.WorkflowResponse;
import robot.agent.dto.response.WorkflowVersionResponse;
import robot.agent.model.Execution;
import robot.agent.model.ExecutionStatus;
import robot.agent.model.Workflow;
import robot.agent.model.WorkflowStatus;
import robot.agent.model.WorkflowVersion;
import robot.agent.model.WorkflowVersionStatus;
import robot.agent.repository.WorkflowRepository;
import robot.agent.repository.WorkflowVersionRepository;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class WorkflowService {

    private final WorkflowRepository workflowRepository;
    private final WorkflowVersionRepository workflowVersionRepository;
    private final ObjectMapper objectMapper;
    private final AccessControlService accessControlService;
    private final AuditService auditService;
    private final PythonClient pythonClient;
    private final ModelConfigService modelConfigService;

    public WorkflowService(
            WorkflowRepository workflowRepository,
            WorkflowVersionRepository workflowVersionRepository,
            ObjectMapper objectMapper,
            AccessControlService accessControlService,
            AuditService auditService,
            PythonClient pythonClient,
            ModelConfigService modelConfigService
    ) {
        this.workflowRepository = workflowRepository;
        this.workflowVersionRepository = workflowVersionRepository;
        this.objectMapper = objectMapper;
        this.accessControlService = accessControlService;
        this.auditService = auditService;
        this.pythonClient = pythonClient;
        this.modelConfigService = modelConfigService;
    }

    public WorkflowResponse createWorkflow(String userId, String workflowCode, String name, String description, Long workspaceId) {
        Long effectiveWorkspaceId = workspaceId != null ? workspaceId : 1L;
        accessControlService.requireWorkflowAdminAction(userId, effectiveWorkspaceId, workflowCode, "workflow.create");

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
        accessControlService.requireWorkflowAdminAction(userId, workflow.getWorkspaceId(), workflowCode, "workflow.publish");
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
        accessControlService.requireWorkflowAdminAction(userId, workflow.getWorkspaceId(), workflowCode, "workflow.rollback");
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
        return saveWorkflowDraft(userId, workflowCode, request);
    }

    public WorkflowVersionResponse saveWorkflowDraft(String userId, String workflowCode, CreateWorkflowVersionRequest request) {
        Workflow workflow = workflowRepository.findByWorkflowCode(workflowCode)
                .orElseThrow(() -> new RuntimeException("Workflow not found: " + workflowCode));
        accessControlService.requireWorkflowAdminAction(userId, workflow.getWorkspaceId(), workflowCode, "workflow.version.create");

        WorkflowVersion version = workflowVersionRepository.findByWorkflowCodeAndVersion(workflowCode, request.getVersion())
                .orElseGet(WorkflowVersion::new);
        version.setWorkflowCode(workflowCode);
        version.setVersion(request.getVersion());
        version.setDefinition(request.getDefinition());
        version.setEntryRule(request.getEntryRule());
        version.setEditorMeta(request.getEditorMeta());
        version.setConfig(request.getConfig());
        version.setStatus(WorkflowVersionStatus.DRAFT);
        version.setCreatedBy(userId);
        if (version.getCreatedAt() == null) {
            version.setCreatedAt(LocalDateTime.now());
        }
        WorkflowVersion saved = workflowVersionRepository.save(version);
        auditService.logAction(workflow.getWorkspaceId(), userId, "workflow.version.save_draft", "workflow_version", workflowCode + ":" + request.getVersion(), request, 200);
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

    public WorkflowVersion getWorkflowVersionEntity(String workflowCode, String version) {
        return workflowVersionRepository.findByWorkflowCodeAndVersion(workflowCode, version)
                .orElseThrow(() -> new RuntimeException("Workflow version not found: " + workflowCode + "@" + version));
    }

    public List<Map<String, Object>> validateWorkflowDefinition(String definitionJson, String configJson) {
        Map<String, Object> definition = parseJsonObject(definitionJson);
        Map<String, Object> workflowConfig = parseJsonObject(configJson);
        List<Map<String, Object>> issues = new ArrayList<>();

        Map<String, Object> nodes = asMap(definition.get("nodes"));
        Object entry = definition.get("entry");
        if (entry == null || String.valueOf(entry).isBlank()) {
            issues.add(issue(null, "entry", "缺少入口节点"));
        } else if (!nodes.containsKey(String.valueOf(entry))) {
            issues.add(issue(String.valueOf(entry), "entry", "入口节点不存在"));
        }

        if (nodes.isEmpty()) {
            issues.add(issue(null, "nodes", "至少需要一个节点"));
            return issues;
        }

        if (!nodes.values().stream().anyMatch(node -> "end".equals(String.valueOf(asMap(node).get("type"))))) {
            issues.add(issue(null, "nodes", "缺少 end 节点"));
        }

        String workflowDefaultProfile = readNestedString(workflowConfig, "llm_defaults", "model_profile_ref");
        String routingProfile = stringValue(workflowConfig.get("intent_profile_ref"));
        if (routingProfile == null) {
            issues.add(issue(null, "config.intent_profile_ref", "未配置意图识别模型 Profile"));
        }

        for (Map.Entry<String, Object> nodeEntry : nodes.entrySet()) {
            String nodeId = nodeEntry.getKey();
            Map<String, Object> node = asMap(nodeEntry.getValue());
            String type = stringValue(node.get("type"));
            Map<String, Object> nodeConfig = asMap(node.get("config"));
            if ("llm".equals(type) && stringValue(nodeConfig.get("model_profile_ref")) == null && workflowDefaultProfile == null) {
                issues.add(issue(nodeId, "config.model_profile_ref", "LLM 节点缺少模型 Profile"));
            }
            if ("knowledge".equals(type)) {
                if (stringValue(nodeConfig.get("knowledge_base_code")) == null) {
                    issues.add(issue(nodeId, "config.knowledge_base_code", "Knowledge 节点缺少知识库编码"));
                }
                Map<String, Object> queryRewrite = asMap(nodeConfig.get("query_rewrite"));
                if (Boolean.TRUE.equals(queryRewrite.get("enabled")) && stringValue(queryRewrite.get("model_profile_ref")) == null) {
                    issues.add(issue(nodeId, "config.query_rewrite.model_profile_ref", "Knowledge 查询改写缺少模型 Profile"));
                }
                Map<String, Object> answerGeneration = asMap(nodeConfig.get("answer_generation"));
                if (Boolean.TRUE.equals(answerGeneration.get("enabled")) && stringValue(answerGeneration.get("model_profile_ref")) == null) {
                    issues.add(issue(nodeId, "config.answer_generation.model_profile_ref", "Knowledge 回答生成缺少模型 Profile"));
                }
            }
            if ("tool".equals(type) && stringValue(nodeConfig.get("tool_code")) == null && stringValue(nodeConfig.get("url")) == null) {
                issues.add(issue(nodeId, "config.tool_code", "Tool 节点缺少真实工具配置"));
            }
        }
        return issues;
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
        List<Map<String, Object>> workflowDefinitions = versions.stream()
                .map(version -> parseJsonObject(version.getDefinition()))
                .toList();
        String routingProfileCode = modelConfigService.resolveRoutingProfileCode(workflowDefinitions);
        ModelConfigService.RuntimeModelBundle runtimeBundle = modelConfigService.buildRuntimeBundle(workflowDefinitions, routingProfileCode);
        ModelIntent modelIntent = classifyIntent(normalizedContent, versions, routingProfileCode, runtimeBundle);
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
        DynamicThreshold dynamicThreshold = resolveDynamicThreshold(best.version().getWorkflowCode(), modelIntent.intentCode(), confidence, normalizedContent);

        if (best.entryRuleScore() == 0 && modelIntent.confidence() < 0.6d) {
            WorkflowVersion fallbackVersion = versions.stream()
                    .filter(version -> "general_query".equals(version.getWorkflowCode()))
                    .findFirst()
                    .orElse(best.version());
            best = new WorkflowScore(fallbackVersion, 0, 0, extractPriority(fallbackVersion.getEntryRule()));
            decision = "fallback";
            reason = "low_confidence_fallback";
            confidence = Math.max(modelIntent.confidence(), 0.55d);
            dynamicThreshold = resolveDynamicThreshold(best.version().getWorkflowCode(), modelIntent.intentCode(), confidence, normalizedContent);
        } else if (!dynamicThreshold.accepted()) {
            WorkflowVersion fallbackVersion = versions.stream()
                    .filter(version -> "general_query".equals(version.getWorkflowCode()))
                    .findFirst()
                    .orElse(best.version());
            best = new WorkflowScore(fallbackVersion, 0, 0, extractPriority(fallbackVersion.getEntryRule()));
            decision = "fallback";
            reason = "dynamic_threshold_fallback";
            confidence = Math.max(confidence, 0.55d);
            dynamicThreshold = resolveDynamicThreshold(best.version().getWorkflowCode(), modelIntent.intentCode(), confidence, normalizedContent);
        }

        if (activeExecution != null
                && !activeExecution.getStatus().isTerminal()
                && activeExecution.getStatus() != ExecutionStatus.SUSPENDED
                && !activeExecution.getWorkflowCode().equals(best.version().getWorkflowCode())
                && dynamicThreshold.accepted()) {
            decision = "switch_required";
            reason = "active_execution_conflict";
        }

        return new RoutingDecision(
                decision,
                best.version().getWorkflowCode(),
                best.version().getVersion(),
                confidence,
                dynamicThreshold.threshold(),
                dynamicThreshold.thresholdSource(),
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

    public RuntimeExecutionBundle buildRuntimeExecutionBundle(String workflowCode, String version) {
        WorkflowVersion workflowVersion = getWorkflowVersionEntity(workflowCode, version);
        Map<String, Map<String, Object>> workflowCatalog = buildWorkflowCatalog();
        Collection<Map<String, Object>> workflowDefinitions = workflowCatalog.values();
        String routingProfileCode = modelConfigService.resolveRoutingProfileCode(workflowDefinitions);
        ModelConfigService.RuntimeModelBundle runtimeBundle = modelConfigService.buildRuntimeBundle(workflowDefinitions, routingProfileCode);
        return new RuntimeExecutionBundle(
                parseJsonObject(workflowVersion.getDefinition()),
                parseJsonObject(workflowVersion.getEntryRule()),
                parseJsonObject(workflowVersion.getConfig()),
                workflowCatalog,
                runtimeBundle.providerConfigs(),
                runtimeBundle.modelProfiles(),
                routingProfileCode
        );
    }

    private ModelIntent classifyIntent(
            String normalizedContent,
            List<WorkflowVersion> versions,
            String routingProfileCode,
            ModelConfigService.RuntimeModelBundle runtimeBundle
    ) {
        List<Map<String, Object>> candidates = versions.stream()
                .map(version -> Map.of(
                        "workflow_code", version.getWorkflowCode(),
                        "workflow_version", version.getVersion(),
                        "entry_rule", parseJsonObject(version.getEntryRule())
                ))
                .toList();
        Map<String, Object> response = pythonClient.classifyIntent(Map.of(
                "message", normalizedContent,
                "intent_profile_code", routingProfileCode,
                "candidate_workflows", candidates,
                "provider_configs", runtimeBundle.providerConfigs(),
                "model_profiles", runtimeBundle.modelProfiles()
        )).blockOptional().orElseThrow(() -> new RuntimeException("Intent classification unavailable"));
        String intentCode = stringValue(response.get("intent_code"));
        String workflowCode = stringValue(response.get("workflow_code"));
        double confidence = toDouble(response.get("confidence"), 0.0d);
        String reason = stringValue(response.get("reason"));
        if (workflowCode == null) {
            throw new RuntimeException("Intent classification returned no workflow_code");
        }
        return new ModelIntent(intentCode == null ? workflowCode : intentCode, workflowCode, confidence, reason);
    }

    private boolean containsAny(String normalizedContent, String... keywords) {
        for (String keyword : keywords) {
            if (normalizedContent.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private DynamicThreshold resolveDynamicThreshold(
            String workflowCode,
            String intentCode,
            double confidence,
            String normalizedContent
    ) {
        double base = switch (workflowCode) {
            case "flight_booking" -> 0.72d;
            case "hotel_booking" -> 0.68d;
            case "general_query" -> 0.52d;
            default -> 0.60d;
        };
        String source = "dynamic:default";

        if (normalizedContent.length() <= 8) {
            base += 0.05d;
            source = "dynamic:short_query";
        } else if (containsAny(normalizedContent, "政策", "规则", "policy", "refund")) {
            base -= 0.04d;
            source = "dynamic:knowledge_query";
        } else if (containsAny(normalizedContent, "航班", "机票", "flight", "ticket")) {
            base -= 0.02d;
            source = "dynamic:travel_query";
        }

        if ("general_query".equals(intentCode)) {
            base = Math.min(base, 0.58d);
        }

        double threshold = Math.max(0.45d, Math.min(0.85d, Math.round(base * 100.0d) / 100.0d));
        return new DynamicThreshold(threshold, source, confidence >= threshold);
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

    private Map<String, Map<String, Object>> buildWorkflowCatalog() {
        List<WorkflowVersion> versions = workflowVersionRepository.findByStatusOrderByCreatedAtDesc(WorkflowVersionStatus.PUBLISHED);
        Map<String, Map<String, Object>> catalog = new LinkedHashMap<>();
        for (WorkflowVersion version : versions) {
            catalog.put(version.getWorkflowCode() + "@" + version.getVersion(), parseJsonObject(version.getDefinition()));
        }
        return catalog;
    }

    private Map<String, Object> parseJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            JsonNode node = readJsonObject(json);
            return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
        } catch (Exception exception) {
            return new LinkedHashMap<>();
        }
    }

    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    converted.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return converted;
        }
        return new LinkedHashMap<>();
    }

    private Map<String, Object> issue(String nodeId, String field, String message) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("node_id", nodeId);
        issue.put("field", field);
        issue.put("message", message);
        return issue;
    }

    private String readNestedString(Map<String, Object> source, String first, String second) {
        Map<String, Object> nested = asMap(source.get(first));
        return stringValue(nested.get(second));
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private double toDouble(Object value, double defaultValue) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? defaultValue : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private record ModelIntent(String intentCode, String workflowCode, double confidence, String reason) {
    }

    private record WorkflowScore(WorkflowVersion version, int entryRuleScore, int modelScore, int priority) {
        int totalScore() {
            return entryRuleScore + modelScore;
        }
    }

    private record DynamicThreshold(double threshold, String thresholdSource, boolean accepted) {
    }

    public record RuntimeExecutionBundle(
            Map<String, Object> workflowDefinition,
            Map<String, Object> entryRule,
            Map<String, Object> workflowConfig,
            Map<String, Map<String, Object>> workflowCatalog,
            List<Map<String, Object>> providerConfigs,
            List<Map<String, Object>> modelProfiles,
            String routingProfileCode
    ) {
    }
}
