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

    private static final String WORKFLOW_SCHEMA_V2 = "workflow-designer/v2";
    private static final String DEFAULT_MAIN_GRAPH_ID = "main";
    private static final Set<String> SUPPORTED_NODE_TYPES = Set.of(
            "start", "coordinator", "sub_agent", "tool", "message", "function", "end"
    );

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
                .orElseGet(() -> {
                    Workflow created = new Workflow();
                    created.setWorkflowCode(workflowCode);
                    created.setName(resolveWorkflowName(request, workflowCode));
                    created.setDescription("Auto-created draft workflow");
                    created.setWorkspaceId(1L);
                    created.setStatus(WorkflowStatus.DRAFT);
                    created.setCreatedBy(userId);
                    created.setCreatedAt(LocalDateTime.now());
                    created.setUpdatedAt(LocalDateTime.now());
                    return workflowRepository.save(created);
                });
        if (request.getWorkflowName() != null && !request.getWorkflowName().isBlank()
                && !request.getWorkflowName().equals(workflow.getName())) {
            workflow.setName(request.getWorkflowName().trim());
            workflow.setUpdatedAt(LocalDateTime.now());
            workflow = workflowRepository.save(workflow);
        }
        accessControlService.requireWorkflowAdminAction(userId, workflow.getWorkspaceId(), workflowCode, "workflow.version.create");

        WorkflowVersion version = workflowVersionRepository.findByWorkflowCodeAndVersion(workflowCode, request.getVersion())
                .orElseGet(WorkflowVersion::new);
        version.setWorkflowCode(workflowCode);
        version.setVersion(request.getVersion());
        version.setDefinition(normalizeDefinitionJsonForPersist(request.getDefinition()));
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
        return WorkflowVersionResponse.fromEntity(saved, workflow);
    }

    public WorkflowVersionResponse getWorkflowVersion(String workflowCode, String version) {
        WorkflowVersion workflowVersion = workflowVersionRepository.findByWorkflowCodeAndVersion(workflowCode, version)
                .orElseThrow(() -> new RuntimeException("Workflow version not found"));
        Workflow workflow = workflowRepository.findByWorkflowCode(workflowCode)
                .orElse(null);
        return WorkflowVersionResponse.fromEntity(workflowVersion, workflow);
    }

    public List<WorkflowVersionResponse> getWorkflowVersions(String workflowCode) {
        List<WorkflowVersion> versions = workflowVersionRepository.findByWorkflowCodeAndStatusNotOrderByCreatedAtDesc(
                workflowCode,
                WorkflowVersionStatus.ARCHIVED
        );
        return versions.stream()
                .map(version -> WorkflowVersionResponse.fromEntity(
                        version,
                        workflowRepository.findByWorkflowCode(version.getWorkflowCode()).orElse(null)
                ))
                .collect(Collectors.toList());
    }

    public WorkflowVersionResponse archiveWorkflowVersion(String userId, String workflowCode, String version) {
        Workflow workflow = workflowRepository.findByWorkflowCode(workflowCode)
                .orElseThrow(() -> new RuntimeException("Workflow not found: " + workflowCode));
        accessControlService.requireWorkflowAdminAction(userId, workflow.getWorkspaceId(), workflowCode, "workflow.version.archive");

        WorkflowVersion workflowVersion = workflowVersionRepository.findByWorkflowCodeAndVersion(workflowCode, version)
                .orElseThrow(() -> new RuntimeException("Workflow version not found: " + workflowCode + "@" + version));
        workflowVersion.setStatus(WorkflowVersionStatus.ARCHIVED);
        WorkflowVersion savedVersion = workflowVersionRepository.save(workflowVersion);

        if (version.equals(workflow.getCurrentVersion())) {
            Optional<WorkflowVersion> fallbackPublishedVersion = workflowVersionRepository
                    .findByWorkflowCodeAndStatusNotOrderByCreatedAtDesc(workflowCode, WorkflowVersionStatus.ARCHIVED)
                    .stream()
                    .filter(item -> item.getStatus() == WorkflowVersionStatus.PUBLISHED)
                    .filter(item -> !version.equals(item.getVersion()))
                    .findFirst();

            if (fallbackPublishedVersion.isPresent()) {
                workflow.setCurrentVersion(fallbackPublishedVersion.get().getVersion());
                workflow.setStatus(WorkflowStatus.PUBLISHED);
            } else {
                workflow.setCurrentVersion(null);
                workflow.setStatus(WorkflowStatus.DRAFT);
            }
            workflow.setUpdatedAt(LocalDateTime.now());
            workflowRepository.save(workflow);
        }

        auditService.logAction(
                workflow.getWorkspaceId(),
                userId,
                "workflow.version.archive",
                "workflow_version",
                workflowCode + ":" + version,
                null,
                200
        );
        return WorkflowVersionResponse.fromEntity(savedVersion, workflow);
    }

    private String resolveWorkflowName(CreateWorkflowVersionRequest request, String workflowCode) {
        if (request.getWorkflowName() != null && !request.getWorkflowName().isBlank()) {
            return request.getWorkflowName().trim();
        }
        return workflowCode;
    }

    public WorkflowVersion getWorkflowVersionEntity(String workflowCode, String version) {
        return workflowVersionRepository.findByWorkflowCodeAndVersion(workflowCode, version)
                .orElseThrow(() -> new RuntimeException("Workflow version not found: " + workflowCode + "@" + version));
    }

    public WorkflowVersion requirePublishedWorkflowVersion(String workflowCode, String version) {
        Workflow workflow = workflowRepository.findByWorkflowCode(workflowCode)
                .orElseThrow(() -> new RuntimeException("Workflow not found: " + workflowCode));
        if (workflow.getStatus() != WorkflowStatus.PUBLISHED) {
            throw new RuntimeException("Workflow is not published: " + workflowCode);
        }

        WorkflowVersion workflowVersion = getWorkflowVersionEntity(workflowCode, version);
        if (workflowVersion.getStatus() != WorkflowVersionStatus.PUBLISHED) {
            throw new RuntimeException("Workflow version is not published: " + workflowCode + "@" + version);
        }
        return workflowVersion;
    }

    public List<Map<String, Object>> validateWorkflowDefinition(String definitionJson, String configJson) {
        Map<String, Object> rawDefinition = parseJsonObject(definitionJson);
        List<Map<String, Object>> issues = new ArrayList<>();
        String rawSchemaVersion = stringValue(rawDefinition.get("schema_version"));
        if (rawSchemaVersion != null && !WORKFLOW_SCHEMA_V2.equals(rawSchemaVersion)) {
            issues.add(issue(null, "schema_version", "schema_version 不受支持: " + rawSchemaVersion));
            return issues;
        }

        Map<String, Object> definition = normalizeWorkflowDefinition(rawDefinition);
        Map<String, Object> graphs = asMap(definition.get("graphs"));
        if (graphs.isEmpty()) {
            issues.add(issue(null, "graphs", "至少需要一个图定义"));
            return issues;
        }

        String mainGraphId = stringValue(definition.get("main_graph_id"));
        if (mainGraphId == null) {
            issues.add(issue(null, "main_graph_id", "缺少主图标识"));
        } else if (!graphs.containsKey(mainGraphId)) {
            issues.add(issue(null, "main_graph_id", "main_graph_id 未引用有效图"));
        }

        long mainGraphCount = graphs.values().stream()
                .map(this::asMap)
                .map(graph -> stringValue(graph.get("graph_type")))
                .filter("main"::equalsIgnoreCase)
                .count();
        if (mainGraphCount != 1) {
            issues.add(issue(null, "graphs.graph_type", "工作流必须且只能有一个 main 图"));
        }
        if (mainGraphId != null) {
            Map<String, Object> mainGraph = asMap(graphs.get(mainGraphId));
            String mainGraphType = stringValue(mainGraph.get("graph_type"));
            if (mainGraphType != null && !"main".equalsIgnoreCase(mainGraphType)) {
                issues.add(issue(null, "main_graph_id", "main_graph_id 指向的图必须是 main"));
            }
        }

        for (Map.Entry<String, Object> graphEntry : graphs.entrySet()) {
            String graphId = graphEntry.getKey();
            Map<String, Object> graph = asMap(graphEntry.getValue());
            boolean subflowGraph = isSubflowGraph(graphId, graph, mainGraphId);
            validateGraph(graphId, graph, graphs, subflowGraph, issues);
        }
        return issues;
    }

    private List<Map<String, Object>> validateWorkflowDefinitionLegacyCompat(String definitionJson, String configJson) {
        Map<String, Object> definition = normalizeWorkflowDefinition(parseJsonObject(definitionJson));
        List<Map<String, Object>> issues = new ArrayList<>();

        Map<String, Object> graphs = asMap(definition.get("graphs"));
        if (graphs.isEmpty()) {
            issues.add(issue(null, "graphs", "至少需要一个图定义"));
            return issues;
        }

        String mainGraphId = stringValue(definition.get("main_graph_id"));
        if (mainGraphId == null) {
            issues.add(issue(null, "main_graph_id", "缺少主图标识"));
        } else if (!graphs.containsKey(mainGraphId)) {
            issues.add(issue(null, "main_graph_id", "main_graph_id 未引用有效图"));
        }

        for (Map.Entry<String, Object> graphEntry : graphs.entrySet()) {
            String graphId = graphEntry.getKey();
            Map<String, Object> graph = asMap(graphEntry.getValue());
            boolean subflowGraph = isSubflowGraph(graphId, graph, mainGraphId);
            validateGraph(graphId, graph, graphs, subflowGraph, issues);
        }
        return issues;
    }

    private List<Map<String, Object>> validateWorkflowDefinitionLegacy(String definitionJson, String configJson) {
        Map<String, Object> definition = parseJsonObject(definitionJson);
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

        long startCount = nodes.values().stream()
                .filter(node -> "start".equals(String.valueOf(asMap(node).get("type"))))
                .count();
        long endCount = nodes.values().stream()
                .filter(node -> "end".equals(String.valueOf(asMap(node).get("type"))))
                .count();

        if (startCount != 1) {
            issues.add(issue(null, "nodes.start", "必须且只能有一个开始节点"));
        }
        if (endCount != 1) {
            issues.add(issue(null, "nodes.end", "必须且只能有一个结束节点"));
        }

        for (Map.Entry<String, Object> nodeEntry : nodes.entrySet()) {
            String nodeId = nodeEntry.getKey();
            Map<String, Object> node = asMap(nodeEntry.getValue());
            String type = stringValue(node.get("type"));
            Map<String, Object> nodeConfig = asMap(node.get("config"));
            if (type == null || !Set.of("start", "coordinate", "sub_agent", "tool", "message", "end").contains(type)) {
                issues.add(issue(nodeId, "type", "节点类型不受支持"));
                continue;
            }
            if (("coordinate".equals(type) || "sub_agent".equals(type) || "start".equals(type) || "end".equals(type))
                    && stringValue(nodeConfig.get("prompt")) == null
                    && stringValue(nodeConfig.get("user_prompt")) == null) {
                issues.add(issue(nodeId, "config.prompt", "提示词节点缺少 prompt"));
            }
            if ("end".equals(type)) {
                Object outputFormat = nodeConfig.get("output_format");
                if (outputFormat != null && !(outputFormat instanceof Map<?, ?>)) {
                    issues.add(issue(nodeId, "config.output_format", "结束节点缺少输出变量映射"));
                }
            }
            if ("message".equals(type) && stringValue(nodeConfig.get("message_text")) == null) {
                issues.add(issue(nodeId, "config.message_text", "消息节点缺少固定话术"));
            }
            if ("tool".equals(type)) {
                validateToolNode(nodeId, nodeConfig, issues);
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
                .map(version -> attachWorkflowConfig(
                        normalizeWorkflowDefinition(parseJsonObject(version.getDefinition())),
                        normalizeWorkflowConfig(parseJsonObject(version.getConfig()))
                ))
                .toList();
        String routingModelCode = modelConfigService.resolveRoutingModelCode(workflowDefinitions);
        ModelConfigService.RuntimeModelBundle runtimeBundle = modelConfigService.buildRuntimeBundle(workflowDefinitions, routingModelCode);
        ModelIntent modelIntent = classifyIntent(normalizedContent, versions, routingModelCode, runtimeBundle);
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
        Map<String, Object> normalizedDefinition = normalizeWorkflowDefinition(parseJsonObject(workflowVersion.getDefinition()));
        return buildRuntimeExecutionBundle(
                workflowCode,
                version,
                normalizedDefinition,
                parseJsonObject(workflowVersion.getEntryRule()),
                normalizeWorkflowConfig(parseJsonObject(workflowVersion.getConfig()))
        );
    }

    public RuntimeExecutionBundle buildRuntimeExecutionBundleForExplicitExecution(String workflowCode, String version) {
        WorkflowVersion workflowVersion = getWorkflowVersionEntity(workflowCode, version);
        Map<String, Object> normalizedDefinition = normalizeWorkflowDefinition(parseJsonObject(workflowVersion.getDefinition()));
        return buildRuntimeExecutionBundleInternal(
                workflowCode,
                version,
                normalizedDefinition,
                parseJsonObject(workflowVersion.getEntryRule()),
                normalizeWorkflowConfig(parseJsonObject(workflowVersion.getConfig())),
                false
        );
    }

    public RuntimeExecutionBundle buildRuntimeExecutionBundle(
            String workflowCode,
            String version,
            Map<String, Object> workflowDefinition,
            Map<String, Object> entryRule,
            Map<String, Object> workflowConfig
    ) {
        return buildRuntimeExecutionBundleInternal(
                workflowCode,
                version,
                workflowDefinition,
                entryRule,
                workflowConfig,
                true
        );
    }

    private RuntimeExecutionBundle buildRuntimeExecutionBundleInternal(
            String workflowCode,
            String version,
            Map<String, Object> workflowDefinition,
            Map<String, Object> entryRule,
            Map<String, Object> workflowConfig,
            boolean includePublishedCatalog
    ) {
        Map<String, Object> normalizedDefinition = normalizeWorkflowDefinition(workflowDefinition);
        Map<String, Object> normalizedWorkflowConfig = normalizeWorkflowConfig(workflowConfig);
        Map<String, Map<String, Object>> workflowCatalog = includePublishedCatalog
                ? buildWorkflowCatalog()
                : new LinkedHashMap<>();
        workflowCatalog.put(workflowCode + "@" + version, normalizedDefinition);
        Collection<Map<String, Object>> workflowDefinitions = List.of(attachWorkflowConfig(normalizedDefinition, normalizedWorkflowConfig));
        String routingModelCode = modelConfigService.resolveRoutingModelCode(workflowDefinitions);
        ModelConfigService.RuntimeModelBundle runtimeBundle = modelConfigService.buildRuntimeBundle(workflowDefinitions, routingModelCode);
        return new RuntimeExecutionBundle(
                normalizedDefinition,
                entryRule,
                normalizedWorkflowConfig,
                workflowCatalog,
                runtimeBundle.providerConfigs(),
                runtimeBundle.modelRecords(),
                routingModelCode
        );
    }

    private ModelIntent classifyIntent(
            String normalizedContent,
            List<WorkflowVersion> versions,
            String routingModelCode,
            ModelConfigService.RuntimeModelBundle runtimeBundle
    ) {
        if (runtimeBundle.providerConfigs().isEmpty() || runtimeBundle.modelRecords().isEmpty()) {
            return fallbackModelIntent(versions, normalizedContent, "model_config_unavailable");
        }
        List<Map<String, Object>> candidates = versions.stream()
                .map(version -> Map.of(
                        "workflow_code", version.getWorkflowCode(),
                        "workflow_version", version.getVersion(),
                        "entry_rule", parseJsonObject(version.getEntryRule())
                ))
                .toList();
        Map<String, Object> response;
        try {
            response = pythonClient.classifyIntent(Map.of(
                    "message", normalizedContent,
                    "routing_model_code", routingModelCode,
                    "candidate_workflows", candidates,
                    "provider_configs", runtimeBundle.providerConfigs(),
                    "model_records", runtimeBundle.modelRecords()
            )).blockOptional().orElseThrow(() -> new RuntimeException("Intent classification unavailable"));
        } catch (RuntimeException exception) {
            return fallbackModelIntent(versions, normalizedContent, "intent_classification_fallback");
        }
        String intentCode = stringValue(response.get("intent_code"));
        String workflowCode = stringValue(response.get("workflow_code"));
        double confidence = toDouble(response.get("confidence"), 0.0d);
        String reason = stringValue(response.get("reason"));
        if (workflowCode == null) {
            return fallbackModelIntent(versions, normalizedContent, "missing_workflow_code");
        }
        return new ModelIntent(intentCode == null ? workflowCode : intentCode, workflowCode, confidence, reason);
    }

    private ModelIntent fallbackModelIntent(List<WorkflowVersion> versions, String normalizedContent, String reason) {
        WorkflowVersion preferred = versions.stream()
                .filter(version -> "general_query".equals(version.getWorkflowCode()))
                .findFirst()
                .orElse(versions.get(0));
        String intentCode = preferred.getWorkflowCode();
        double confidence = normalizedContent.isBlank() ? 0.55d : 0.6d;
        return new ModelIntent(intentCode, preferred.getWorkflowCode(), confidence, reason);
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
            catalog.put(
                    version.getWorkflowCode() + "@" + version.getVersion(),
                    normalizeWorkflowDefinition(parseJsonObject(version.getDefinition()))
            );
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

    private String normalizeDefinitionJsonForPersist(String definitionJson) {
        Map<String, Object> parsed = parseJsonObjectStrict(definitionJson);
        Map<String, Object> normalized = normalizeWorkflowDefinition(parsed);
        return writeJsonObject(normalized);
    }

    private Map<String, Object> parseJsonObjectStrict(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            JsonNode node = readJsonObject(json);
            return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
        } catch (Exception exception) {
            throw new RuntimeException("Workflow definition JSON is invalid");
        }
    }

    private String writeJsonObject(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? new LinkedHashMap<>() : value);
        } catch (Exception exception) {
            throw new RuntimeException("Workflow definition serialization failed");
        }
    }

    private Map<String, Object> normalizeWorkflowDefinition(Map<String, Object> rawDefinition) {
        Map<String, Object> source = deepCopyMap(rawDefinition);
        String schemaVersion = stringValue(source.get("schema_version"));
        if (schemaVersion != null && !WORKFLOW_SCHEMA_V2.equals(schemaVersion)) {
            throw new RuntimeException("Unsupported schema_version: " + schemaVersion);
        }
        Map<String, Object> normalized = new LinkedHashMap<>();

        Map<String, Object> graphs = normalizeGraphs(source);
        String mainGraphId = firstNonBlank(stringValue(source.get("main_graph_id")));
        if (mainGraphId == null || !graphs.containsKey(mainGraphId)) {
            mainGraphId = graphs.containsKey(DEFAULT_MAIN_GRAPH_ID)
                    ? DEFAULT_MAIN_GRAPH_ID
                    : graphs.keySet().stream().findFirst().orElse(DEFAULT_MAIN_GRAPH_ID);
        }

        Map<String, Object> variables = asMap(source.get("variables"));
        Map<String, Object> modelBindings = normalizeModelReferences(asMap(source.get("model_bindings")));
        Map<String, Object> editorMeta = asMap(source.get("editor_meta"));
        Map<String, Object> legacyConfig = normalizeWorkflowConfig(asMap(source.get("config")));

        if (variables.isEmpty()
                && (legacyConfig.get("global_variables") instanceof List<?> || legacyConfig.get("temporary_variables") instanceof List<?>)) {
            variables = new LinkedHashMap<>();
            variables.put("global", legacyConfig.getOrDefault("global_variables", List.of()));
            variables.put("temporary", legacyConfig.getOrDefault("temporary_variables", List.of()));
        }
        if (modelBindings.isEmpty()) {
            modelBindings = normalizeModelReferences(asMap(legacyConfig.get("llm_defaults")));
        }
        if (editorMeta.isEmpty()) {
            editorMeta = asMap(source.get("meta"));
        }

        normalized.put("schema_version", WORKFLOW_SCHEMA_V2);
        normalized.put("main_graph_id", mainGraphId);
        normalized.put("graphs", graphs);
        normalized.put("variables", variables);
        normalized.put("model_bindings", modelBindings);
        normalized.put("editor_meta", editorMeta);
        normalized.put("workflow_code", source.get("workflow_code"));
        normalized.put("workflow_name", source.get("workflow_name"));
        if (!legacyConfig.isEmpty()) {
            normalized.put("config", legacyConfig);
        }
        return normalizeModelReferences(normalized);
    }

    private Map<String, Object> normalizeGraphs(Map<String, Object> source) {
        Map<String, Object> rawGraphs = asMap(source.get("graphs"));
        Map<String, Object> normalizedGraphs = new LinkedHashMap<>();
        if (!rawGraphs.isEmpty()) {
            for (Map.Entry<String, Object> graphEntry : rawGraphs.entrySet()) {
                String graphId = graphEntry.getKey();
                Map<String, Object> rawGraph = asMap(graphEntry.getValue());
                boolean isMain = DEFAULT_MAIN_GRAPH_ID.equals(graphId)
                        || graphId.equals(stringValue(source.get("main_graph_id")));
                normalizedGraphs.put(graphId, normalizeGraph(graphId, rawGraph, source, isMain));
            }
            return normalizedGraphs;
        }

        Map<String, Object> legacyGraph = new LinkedHashMap<>();
        legacyGraph.put("graph_id", DEFAULT_MAIN_GRAPH_ID);
        legacyGraph.put("graph_type", "main");
        legacyGraph.put("entry_node_id", firstNonBlank(
                stringValue(source.get("entry_node_id")),
                stringValue(source.get("entry")),
                "start"
        ));
        legacyGraph.put("nodes", source.getOrDefault("nodes", Map.of()));
        legacyGraph.put("edges", normalizeEdges(source.get("edges"), source.get("transitions")));
        normalizedGraphs.put(DEFAULT_MAIN_GRAPH_ID, normalizeGraph(DEFAULT_MAIN_GRAPH_ID, legacyGraph, source, true));
        return normalizedGraphs;
    }

    private Map<String, Object> normalizeGraph(
            String graphId,
            Map<String, Object> rawGraph,
            Map<String, Object> rootDefinition,
            boolean mainGraph
    ) {
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("graph_id", firstNonBlank(stringValue(rawGraph.get("graph_id")), graphId));
        graph.put("graph_type", firstNonBlank(stringValue(rawGraph.get("graph_type")), mainGraph ? "main" : "subflow"));
        graph.put("entry_node_id", firstNonBlank(
                stringValue(rawGraph.get("entry_node_id")),
                stringValue(rawGraph.get("entry")),
                mainGraph ? firstNonBlank(stringValue(rootDefinition.get("entry_node_id")), stringValue(rootDefinition.get("entry"))) : null
        ));
        graph.put("nodes", normalizeNodes(asMap(rawGraph.get("nodes"))));
        graph.put("edges", normalizeEdges(rawGraph.get("edges"), rawGraph.get("transitions")));
        return graph;
    }

    private Map<String, Object> normalizeNodes(Map<String, Object> rawNodes) {
        Map<String, Object> normalizedNodes = new LinkedHashMap<>();
        for (Map.Entry<String, Object> nodeEntry : rawNodes.entrySet()) {
            String nodeId = nodeEntry.getKey();
            Map<String, Object> rawNode = asMap(nodeEntry.getValue());
            Map<String, Object> node = new LinkedHashMap<>(rawNode);
            String normalizedType = normalizeNodeType(stringValue(rawNode.get("type")));
            if (normalizedType != null) {
                node.put("type", normalizedType);
            }
            node.put("id", firstNonBlank(stringValue(rawNode.get("id")), nodeId));
            Map<String, Object> config = normalizeModelReferences(new LinkedHashMap<>(asMap(rawNode.get("config"))));
            if ("sub_agent".equals(normalizedType)) {
                String subgraphId = resolveSubgraphId(config);
                if (subgraphId != null) {
                    config.put("subgraph_id", subgraphId);
                }
            }
            node.put("config", config);
            normalizedNodes.put(nodeId, node);
        }
        return normalizedNodes;
    }

    private List<Map<String, Object>> normalizeEdges(Object rawEdges, Object rawTransitions) {
        List<Map<String, Object>> edges = asListOfMaps(rawEdges);
        if (edges.isEmpty()) {
            return convertTransitionsToEdges(asMap(rawTransitions));
        }
        List<Map<String, Object>> normalizedEdges = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> edge : edges) {
            String source = firstNonBlank(stringValue(edge.get("source")), stringValue(edge.get("from")));
            String target = firstNonBlank(stringValue(edge.get("target")), stringValue(edge.get("to")));
            if (source == null || target == null) {
                continue;
            }
            Map<String, Object> normalizedEdge = new LinkedHashMap<>(edge);
            normalizedEdge.put("id", firstNonBlank(stringValue(edge.get("id")), source + "_to_" + target + "_" + (++index)));
            normalizedEdge.put("source", source);
            normalizedEdge.put("target", target);
            normalizedEdges.add(normalizedEdge);
        }
        return normalizedEdges;
    }

    private List<Map<String, Object>> convertTransitionsToEdges(Map<String, Object> transitions) {
        List<Map<String, Object>> edges = new ArrayList<>();
        int[] index = new int[]{0};
        for (Map.Entry<String, Object> transitionEntry : transitions.entrySet()) {
            appendTransitionTargets(edges, transitionEntry.getKey(), transitionEntry.getValue(), index);
        }
        return edges;
    }

    private Map<String, Object> attachWorkflowConfig(
            Map<String, Object> workflowDefinition,
            Map<String, Object> workflowConfig
    ) {
        Map<String, Object> attached = deepCopyMap(workflowDefinition);
        if (workflowConfig != null && !workflowConfig.isEmpty()) {
            attached.put("config", normalizeWorkflowConfig(workflowConfig));
        }
        return attached;
    }

    private Map<String, Object> normalizeWorkflowConfig(Map<String, Object> workflowConfig) {
        Map<String, Object> normalized = normalizeModelReferences(workflowConfig);
        if (normalized.isEmpty()) {
            return normalized;
        }
        String routingModelCode = firstNonBlank(stringValue(normalized.get("routing_model_code")));
        if (routingModelCode != null) {
            normalized.put("routing_model_code", routingModelCode);
        } else {
            normalized.remove("routing_model_code");
        }
        Object llmDefaults = normalized.get("llm_defaults");
        if (llmDefaults instanceof Map<?, ?>) {
            normalized.put("llm_defaults", normalizeModelReferences(asMap(llmDefaults)));
        }
        return normalized;
    }

    private Map<String, Object> normalizeModelReferences(Map<String, Object> source) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            normalized.put(key, normalizeModelReferenceValue(entry.getValue()));
        }
        return normalized;
    }

    private Object normalizeModelReferenceValue(Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            return normalizeModelReferences(asMap(mapValue));
        }
        if (value instanceof List<?> listValue) {
            List<Object> normalized = new ArrayList<>();
            for (Object item : listValue) {
                normalized.add(normalizeModelReferenceValue(item));
            }
            return normalized;
        }
        return value;
    }

    private void appendTransitionTargets(
            List<Map<String, Object>> edges,
            String source,
            Object rawTarget,
            int[] index
    ) {
        if (rawTarget instanceof Map<?, ?> mapTarget) {
            for (Object branchTarget : mapTarget.values()) {
                appendTransitionTargets(edges, source, branchTarget, index);
            }
            return;
        }
        if (rawTarget instanceof List<?> listTarget) {
            for (Object targetItem : listTarget) {
                appendTransitionTargets(edges, source, targetItem, index);
            }
            return;
        }

        String target = stringValue(rawTarget);
        if (source == null || source.isBlank() || target == null || target.isBlank()) {
            return;
        }
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("id", source + "_to_" + target + "_" + (++index[0]));
        edge.put("source", source);
        edge.put("target", target);
        edges.add(edge);
    }

    private void validateGraph(
            String graphId,
            Map<String, Object> graph,
            Map<String, Object> allGraphs,
            boolean subflowGraph,
            List<Map<String, Object>> issues
    ) {
        Map<String, Object> nodes = asMap(graph.get("nodes"));
        if (nodes.isEmpty()) {
            issues.add(issue(null, "graphs." + graphId + ".nodes", "图 " + graphId + " 至少需要一个节点"));
            return;
        }

        String entryNodeId = firstNonBlank(stringValue(graph.get("entry_node_id")), stringValue(graph.get("entry")));
        if (entryNodeId == null) {
            issues.add(issue(null, "graphs." + graphId + ".entry_node_id", "图 " + graphId + " 缺少入口节点"));
        } else if (!nodes.containsKey(entryNodeId)) {
            issues.add(issue(entryNodeId, "graphs." + graphId + ".entry_node_id", "图 " + graphId + " 的入口节点不存在"));
        }

        List<Map<String, Object>> edges = asListOfMaps(graph.get("edges"));
        Map<String, Long> outgoingCount = edges.stream()
                .map(edge -> stringValue(edge.get("source")))
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(source -> source, Collectors.counting()));

        for (Map<String, Object> edge : edges) {
            String source = stringValue(edge.get("source"));
            String target = stringValue(edge.get("target"));
            if (source == null || target == null) {
                issues.add(issue(null, "graphs." + graphId + ".edges", "图 " + graphId + " 包含无效边定义"));
                continue;
            }
            if (!nodes.containsKey(source)) {
                issues.add(issue(source, "graphs." + graphId + ".edges.source", "图 " + graphId + " 的边 source 不存在"));
            }
            if (!nodes.containsKey(target)) {
                issues.add(issue(target, "graphs." + graphId + ".edges.target", "图 " + graphId + " 的边 target 不存在"));
            }
        }

        long startCount = nodes.values().stream()
                .filter(node -> "start".equals(normalizeNodeType(stringValue(asMap(node).get("type")))))
                .count();
        long endCount = nodes.values().stream()
                .filter(node -> "end".equals(normalizeNodeType(stringValue(asMap(node).get("type")))))
                .count();
        boolean legacyMainGraph = !subflowGraph && (startCount > 0 || endCount > 0);

        if (subflowGraph || legacyMainGraph) {
            if (startCount != 1) {
                issues.add(issue(null, "graphs." + graphId + ".nodes.start", "图 " + graphId + " 必须且只能有一个 start 节点"));
            }
            if (endCount != 1) {
                issues.add(issue(null, "graphs." + graphId + ".nodes.end", "图 " + graphId + " 必须且只能有一个 end 节点"));
            }
        } else {
            long coordinatorCount = nodes.values().stream()
                    .filter(node -> "coordinator".equals(normalizeNodeType(stringValue(asMap(node).get("type")))))
                    .count();
            if (coordinatorCount < 1) {
                issues.add(issue(null, "graphs." + graphId + ".nodes.coordinator", "主流程至少需要一个 coordinator 节点"));
            }
        }

        for (Map.Entry<String, Object> nodeEntry : nodes.entrySet()) {
            String nodeId = nodeEntry.getKey();
            Map<String, Object> node = asMap(nodeEntry.getValue());
            String type = normalizeNodeType(stringValue(node.get("type")));
            Map<String, Object> nodeConfig = asMap(node.get("config"));

            if (type == null || !SUPPORTED_NODE_TYPES.contains(type)) {
                issues.add(issue(nodeId, "type", "节点类型不受支持"));
                continue;
            }

            if (subflowGraph && "coordinator".equals(type)) {
                issues.add(issue(nodeId, "type", "子流程中不允许出现 coordinator 节点"));
            }
            if (!subflowGraph && !legacyMainGraph && !Set.of("coordinator", "sub_agent").contains(type)) {
                issues.add(issue(nodeId, "type", "主流程只允许 coordinator 或 sub_agent 节点"));
            }

            long outgoing = outgoingCount.getOrDefault(nodeId, 0L);
            if (outgoing > 1 && !Set.of("coordinator", "sub_agent").contains(type)) {
                issues.add(issue(nodeId, "edges", "只有 coordinator 或 sub_agent 节点允许存在多条出边"));
            }

            if (requiresPrompt(type)
                    && stringValue(nodeConfig.get("prompt")) == null
                    && stringValue(nodeConfig.get("user_prompt")) == null) {
                issues.add(issue(nodeId, "config.prompt", "提示词节点缺少 prompt"));
            }
            if ("end".equals(type)) {
                Object outputFormat = nodeConfig.get("output_format");
                if (outputFormat != null && !(outputFormat instanceof Map<?, ?>)) {
                    issues.add(issue(nodeId, "config.output_format", "结束节点缺少输出变量映射"));
                }
            }
            if ("message".equals(type) && stringValue(nodeConfig.get("message_text")) == null) {
                issues.add(issue(nodeId, "config.message_text", "消息节点缺少固定话术"));
            }
            if ("sub_agent".equals(type)) {
                String subgraphId = resolveSubgraphId(nodeConfig);
                if (subgraphId == null || !allGraphs.containsKey(subgraphId)) {
                    issues.add(issue(nodeId, "config.subgraph_id", "sub_agent 节点的 subgraph_id 必须引用已存在的子图"));
                }
            }
            if ("tool".equals(type)) {
                validateToolNode(nodeId, nodeConfig, issues);
            }
        }

        if (!subflowGraph && !legacyMainGraph) {
            for (Map<String, Object> edge : edges) {
                String source = stringValue(edge.get("source"));
                String target = stringValue(edge.get("target"));
                if (source == null || target == null || !nodes.containsKey(source) || !nodes.containsKey(target)) {
                    continue;
                }
                String sourceType = normalizeNodeType(stringValue(asMap(nodes.get(source)).get("type")));
                String targetType = normalizeNodeType(stringValue(asMap(nodes.get(target)).get("type")));
                if (!"coordinator".equals(sourceType) || !"sub_agent".equals(targetType)) {
                    issues.add(issue(source, "edges", "主流程中只允许 coordinator 连接到 sub_agent"));
                }
            }
        }
    }

    private boolean isSubflowGraph(String graphId, Map<String, Object> graph, String mainGraphId) {
        String graphType = stringValue(graph.get("graph_type"));
        if ("subflow".equalsIgnoreCase(graphType)) {
            return true;
        }
        return mainGraphId != null && !mainGraphId.equals(graphId);
    }

    private boolean requiresPrompt(String nodeType) {
        return Set.of("coordinator", "sub_agent", "start", "end").contains(nodeType);
    }

    private String normalizeNodeType(String rawType) {
        if (rawType == null) {
            return null;
        }
        String normalized = rawType.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "coordinate" -> "coordinator";
            case "subflow" -> "sub_agent";
            default -> normalized;
        };
    }

    private String resolveSubgraphId(Map<String, Object> config) {
        return firstNonBlank(
                stringValue(config.get("subgraph_id")),
                stringValue(config.get("subgraphId")),
                stringValue(config.get("subflow_id")),
                stringValue(config.get("subflowId"))
        );
    }

    private List<Map<String, Object>> asListOfMaps(Object value) {
        if (!(value instanceof List<?> listValue)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : listValue) {
            if (item instanceof Map<?, ?>) {
                result.add(asMap(item));
            }
        }
        return result;
    }

    private String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private Map<String, Object> deepCopyMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(source, new TypeReference<Map<String, Object>>() {});
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

    private void validateToolNode(String nodeId, Map<String, Object> nodeConfig, List<Map<String, Object>> issues) {
        String invokeType = stringValue(nodeConfig.get("invoke_type"));
        if (invokeType == null) {
            issues.add(issue(nodeId, "config.invoke_type", "工具节点缺少调用形式"));
            return;
        }
        switch (invokeType) {
            case "function" -> {
                if (stringValue(nodeConfig.get("function_name")) == null) {
                    issues.add(issue(nodeId, "config.function_name", "函数调用缺少 function_name"));
                }
            }
            case "api" -> {
                if (stringValue(nodeConfig.get("url")) == null) {
                    issues.add(issue(nodeId, "config.url", "API 调用缺少 url"));
                }
                if (stringValue(nodeConfig.get("method")) == null) {
                    issues.add(issue(nodeId, "config.method", "API 调用缺少 method"));
                }
            }
            case "mcp" -> {
                if (stringValue(nodeConfig.get("mcp_endpoint")) == null) {
                    issues.add(issue(nodeId, "config.mcp_endpoint", "MCP 调用缺少 mcp_endpoint"));
                }
                if (stringValue(nodeConfig.get("tool_name")) == null) {
                    issues.add(issue(nodeId, "config.tool_name", "MCP 调用缺少 tool_name"));
                }
            }
            case "skill" -> {
                if (stringValue(nodeConfig.get("skill_endpoint")) == null) {
                    issues.add(issue(nodeId, "config.skill_endpoint", "Skill 调用缺少 skill_endpoint"));
                }
                if (stringValue(nodeConfig.get("skill_name")) == null) {
                    issues.add(issue(nodeId, "config.skill_name", "Skill 调用缺少 skill_name"));
                }
            }
            case "capability" -> {
                if (stringValue(nodeConfig.get("group_id")) == null && stringValue(nodeConfig.get("group_code")) == null) {
                    issues.add(issue(nodeId, "config.group_id", "Capability 调用缺少 group_id"));
                }
                if (stringValue(nodeConfig.get("capability_code")) == null) {
                    issues.add(issue(nodeId, "config.capability_code", "Capability 调用缺少 capability_code"));
                }
            }
            default -> issues.add(issue(nodeId, "config.invoke_type", "工具节点调用形式不受支持"));
        }
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
            List<Map<String, Object>> modelRecords,
            String routingModelCode
    ) {
    }
}
