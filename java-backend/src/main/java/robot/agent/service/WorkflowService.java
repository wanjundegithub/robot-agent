package robot.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import robot.agent.common.ApplicationConstants;
import robot.agent.config.WorkflowPromptProperties;
import robot.agent.config.WorkflowRoutingProperties;
import robot.agent.dto.request.CreateWorkflowVersionRequest;
import robot.agent.dto.request.KnowledgeSearchRequest;
import robot.agent.dto.response.KnowledgeSearchResponse;
import robot.agent.dto.response.WorkflowResponse;
import robot.agent.dto.response.WorkflowVersionResponse;
import robot.agent.model.Execution;
import robot.agent.model.ExecutionStatus;
import robot.agent.model.KnowledgeBinding;
import robot.agent.model.KnowledgeBindingScope;
import robot.agent.model.Workflow;
import robot.agent.model.WorkflowStatus;
import robot.agent.model.WorkflowVersion;
import robot.agent.model.WorkflowVersionStatus;
import robot.agent.repository.WorkflowRepository;
import robot.agent.repository.WorkflowVersionRepository;
import robot.agent.service.knowledge.KnowledgeBindingService;
import robot.agent.service.knowledge.KnowledgeRouteDecisionService;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);
    private static final String WORKFLOW_SCHEMA_V2 = "workflow-designer/v2";
    private static final String WORKFLOW_SNAPSHOT_SCHEMA_V1 = "workflow-snapshot/v1";
    private static final String DEFAULT_MAIN_GRAPH_ID = "main";
    private static final Set<String> SUPPORTED_NODE_TYPES = Set.of(
            "start", "coordinator", "sub_agent", "tool", "api", "message", "function", "end"
    );

    private final WorkflowRepository workflowRepository;
    private final WorkflowVersionRepository workflowVersionRepository;
    private final ObjectMapper objectMapper;
    private final AccessControlService accessControlService;
    private final AuditService auditService;
    private final PythonClient pythonClient;
    private final ModelConfigService modelConfigService;
    private final WorkflowPromptProperties workflowPromptProperties;
    private final WorkflowRoutingProperties workflowRoutingProperties;
    private final KnowledgeBindingService knowledgeBindingService;
    private final KnowledgeRouteDecisionService knowledgeRouteDecisionService;
    private final KnowledgeService knowledgeService;

    @Autowired
    public WorkflowService(
            WorkflowRepository workflowRepository,
            WorkflowVersionRepository workflowVersionRepository,
            ObjectMapper objectMapper,
            AccessControlService accessControlService,
            AuditService auditService,
            PythonClient pythonClient,
            ModelConfigService modelConfigService,
            WorkflowPromptProperties workflowPromptProperties,
            WorkflowRoutingProperties workflowRoutingProperties,
            KnowledgeBindingService knowledgeBindingService,
            KnowledgeRouteDecisionService knowledgeRouteDecisionService,
            KnowledgeService knowledgeService
    ) {
        this.workflowRepository = workflowRepository;
        this.workflowVersionRepository = workflowVersionRepository;
        this.objectMapper = objectMapper;
        this.accessControlService = accessControlService;
        this.auditService = auditService;
        this.pythonClient = pythonClient;
        this.modelConfigService = modelConfigService;
        this.workflowPromptProperties = workflowPromptProperties;
        this.workflowRoutingProperties = workflowRoutingProperties;
        this.knowledgeBindingService = knowledgeBindingService;
        this.knowledgeRouteDecisionService = knowledgeRouteDecisionService;
        this.knowledgeService = knowledgeService;
    }

    public WorkflowService(
            WorkflowRepository workflowRepository,
            WorkflowVersionRepository workflowVersionRepository,
            ObjectMapper objectMapper,
            AccessControlService accessControlService,
            AuditService auditService,
            PythonClient pythonClient,
            ModelConfigService modelConfigService,
            WorkflowPromptProperties workflowPromptProperties,
            WorkflowRoutingProperties workflowRoutingProperties
    ) {
        this(
                workflowRepository,
                workflowVersionRepository,
                objectMapper,
                accessControlService,
                auditService,
                pythonClient,
                modelConfigService,
                workflowPromptProperties,
                workflowRoutingProperties,
                null,
                null,
                null
        );
    }

    public WorkflowService(
            WorkflowRepository workflowRepository,
            WorkflowVersionRepository workflowVersionRepository,
            ObjectMapper objectMapper,
            AccessControlService accessControlService,
            AuditService auditService,
            PythonClient pythonClient,
            ModelConfigService modelConfigService
    ) {
        this(
                workflowRepository,
                workflowVersionRepository,
                objectMapper,
                accessControlService,
                auditService,
                pythonClient,
                modelConfigService,
                new WorkflowPromptProperties(),
                new WorkflowRoutingProperties()
        );
    }

    public WorkflowService(
            WorkflowRepository workflowRepository,
            WorkflowVersionRepository workflowVersionRepository,
            ObjectMapper objectMapper,
            AccessControlService accessControlService,
            AuditService auditService,
            PythonClient pythonClient,
            ModelConfigService modelConfigService,
            WorkflowRoutingProperties workflowRoutingProperties
    ) {
        this(
                workflowRepository,
                workflowVersionRepository,
                objectMapper,
                accessControlService,
                auditService,
                pythonClient,
                modelConfigService,
                new WorkflowPromptProperties(),
                workflowRoutingProperties
        );
    }


    public WorkflowResponse createWorkflow(String userId, String workflowCode, String name, String description, Long workspaceId) {
        Long effectiveWorkspaceId = workspaceId != null ? workspaceId : ApplicationConstants.DEFAULT_WORKSPACE_ID;
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
        auditService.logAction(effectiveWorkspaceId, userId, "workflow.create", "workflow_definition", workflowCode, null, ApplicationConstants.HTTP_STATUS_OK);
        return WorkflowResponse.fromEntity(saved);
    }

    public List<WorkflowResponse> getAllWorkflows() {
        List<Workflow> workflows = workflowRepository.findByStatusNotOrderByCreatedAtDesc(WorkflowStatus.ARCHIVED);
        return workflows.stream()
                .filter(this::isVisibleWorkflow)
                .map(WorkflowResponse::fromEntity)
                .collect(Collectors.toList());
    }

    public List<WorkflowResponse> getPublishedWorkflows() {
        List<Workflow> workflows = workflowRepository.findByStatusOrderByCreatedAtDesc(WorkflowStatus.PUBLISHED);
        return workflows.stream()
                .filter(this::isVisibleWorkflow)
                .filter(this::isUserManagedWorkflow)
                .map(WorkflowResponse::fromEntity)
                .collect(Collectors.toList());
    }

    public WorkflowResponse getWorkflowByCode(String workflowCode) {
        Workflow workflow = workflowRepository.findByWorkflowCode(workflowCode)
                .orElseThrow(() -> new RuntimeException("Workflow not found: " + workflowCode));
        return WorkflowResponse.fromEntity(workflow);
    }

    public void deleteWorkflow(String userId, String workflowCode) {
        Workflow workflow = workflowRepository.findByWorkflowCode(workflowCode)
                .orElseThrow(() -> new RuntimeException("Workflow not found: " + workflowCode));
        accessControlService.requireWorkflowAdminAction(userId, workflow.getWorkspaceId(), workflowCode, "workflow.delete");

        workflow.setStatus(WorkflowStatus.ARCHIVED);
        workflow.setCurrentVersion(null);
        workflow.setUpdatedAt(LocalDateTime.now());
        workflowRepository.save(workflow);

        auditService.logAction(
                workflow.getWorkspaceId(),
                userId,
                "workflow.delete",
                "workflow_definition",
                workflowCode,
                null,
                ApplicationConstants.HTTP_STATUS_OK
        );
    }

    public WorkflowResponse publishWorkflow(String userId, String workflowCode, String version) {
        log.info("workflow.publish.start userId={} workflowCode={} version={}", userId, workflowCode, version);
        Workflow workflow = workflowRepository.findByWorkflowCode(workflowCode)
                .orElseThrow(() -> new RuntimeException("Workflow not found: " + workflowCode));
        accessControlService.requireWorkflowAdminAction(userId, workflow.getWorkspaceId(), workflowCode, "workflow.publish");
        WorkflowVersion workflowVersion = workflowVersionRepository.findByWorkflowCodeAndVersion(workflowCode, version)
                .orElseThrow(() -> new RuntimeException("Workflow version not found: " + workflowCode + "@" + version));
        List<Map<String, Object>> validationIssues = validateWorkflowDefinition(
                workflowVersion.getDefinition(),
                workflowVersion.getConfig()
        );
        if (!validationIssues.isEmpty()) {
            String issueSummary = summarizeValidationIssues(validationIssues);
            log.warn(
                    "workflow.publish.validation_failed workflowCode={} version={} issueCount={} issues={}",
                    workflowCode,
                    version,
                    validationIssues.size(),
                    issueSummary
            );
            throw new IllegalArgumentException("工作流发布校验失败：" + issueSummary);
        }
        if (workflowVersion.getWorkflowSnapshot() == null || workflowVersion.getWorkflowSnapshot().isBlank()) {
            log.info("workflow.publish.snapshot.rebuild workflowCode={} version={} reason=missing_snapshot", workflowCode, version);
            workflowVersion.setWorkflowSnapshot(buildCompatibilityWorkflowSnapshot(
                    workflowCode,
                    workflow.getName(),
                    workflow.getDescription(),
                    version,
                    workflowVersion.getDefinition(),
                    workflowVersion.getEntryRule(),
                    workflowVersion.getEditorMeta(),
                    workflowVersion.getConfig()
            ));
        }
        workflowVersion.setStatus(WorkflowVersionStatus.PUBLISHED);
        workflowVersion.setPublishedAt(LocalDateTime.now());
        workflowVersionRepository.save(workflowVersion);

        workflow.setStatus(WorkflowStatus.PUBLISHED);
        workflow.setCurrentVersion(version);
        workflow.setUpdatedAt(LocalDateTime.now());
        Workflow saved = workflowRepository.save(workflow);
        log.info(
                "workflow.publish.persisted workflowCode={} version={} workflowStatus={} versionStatus={} currentVersion={}",
                workflowCode,
                version,
                saved.getStatus(),
                workflowVersion.getStatus(),
                saved.getCurrentVersion()
        );
        auditService.logAction(workflow.getWorkspaceId(), userId, "workflow.publish", "workflow_definition", workflowCode, version, ApplicationConstants.HTTP_STATUS_OK);

        return WorkflowResponse.fromEntity(saved);
    }

    public WorkflowResponse rollbackWorkflow(String userId, String workflowCode, String version) {
        Workflow workflow = workflowRepository.findByWorkflowCode(workflowCode)
                .orElseThrow(() -> new RuntimeException("Workflow not found: " + workflowCode));
        accessControlService.requireWorkflowAdminAction(userId, workflow.getWorkspaceId(), workflowCode, "workflow.rollback");
        WorkflowVersion workflowVersion = workflowVersionRepository.findByWorkflowCodeAndVersion(workflowCode, version)
                .orElseThrow(() -> new RuntimeException("Workflow version not found: " + workflowCode + "@" + version));

        boolean snapshotMissing = workflowVersion.getWorkflowSnapshot() == null || workflowVersion.getWorkflowSnapshot().isBlank();
        boolean statusNeedsPublish = workflowVersion.getStatus() != WorkflowVersionStatus.PUBLISHED;
        if (snapshotMissing) {
            workflowVersion.setWorkflowSnapshot(buildCompatibilityWorkflowSnapshot(
                    workflowCode,
                    workflow.getName(),
                    workflow.getDescription(),
                    version,
                    workflowVersion.getDefinition(),
                    workflowVersion.getEntryRule(),
                    workflowVersion.getEditorMeta(),
                    workflowVersion.getConfig()
            ));
        }
        if (statusNeedsPublish) {
            workflowVersion.setStatus(WorkflowVersionStatus.PUBLISHED);
            workflowVersion.setPublishedAt(LocalDateTime.now());
        }
        if (snapshotMissing || statusNeedsPublish) {
            workflowVersionRepository.save(workflowVersion);
        }

        workflow.setCurrentVersion(version);
        workflow.setStatus(WorkflowStatus.PUBLISHED);
        workflow.setUpdatedAt(LocalDateTime.now());
        Workflow saved = workflowRepository.save(workflow);
        auditService.logAction(workflow.getWorkspaceId(), userId, "workflow.rollback", "workflow_definition", workflowCode, version, ApplicationConstants.HTTP_STATUS_OK);
        return WorkflowResponse.fromEntity(saved);
    }

    public WorkflowVersionResponse createWorkflowVersion(String userId, String workflowCode, CreateWorkflowVersionRequest request) {
        return saveWorkflowDraft(userId, workflowCode, request);
    }

    public WorkflowVersionResponse saveWorkflowDraft(String userId, String workflowCode, CreateWorkflowVersionRequest request) {
        log.info(
                "workflow.draft.save.start userId={} workflowCode={} version={} hasSnapshot={} hasEditorMeta={} hasConfig={}",
                userId,
                workflowCode,
                request == null ? null : request.getVersion(),
                request != null && request.getWorkflowSnapshot() != null && !request.getWorkflowSnapshot().isBlank(),
                request != null && request.getEditorMeta() != null && !request.getEditorMeta().isBlank(),
                request != null && request.getConfig() != null && !request.getConfig().isBlank()
        );
        Workflow workflow = workflowRepository.findByWorkflowCode(workflowCode)
                .orElseGet(() -> {
                    Workflow created = new Workflow();
                    created.setWorkflowCode(workflowCode);
                    created.setName(resolveWorkflowName(request, workflowCode));
                    created.setDescription(resolveWorkflowDescription(request));
                    created.setWorkspaceId(ApplicationConstants.DEFAULT_WORKSPACE_ID);
                    created.setStatus(WorkflowStatus.DRAFT);
                    created.setCreatedBy(userId);
                    created.setCreatedAt(LocalDateTime.now());
                    created.setUpdatedAt(LocalDateTime.now());
                    return workflowRepository.save(created);
                });
        boolean workflowMetadataChanged = false;
        if (request.getWorkflowName() != null && !request.getWorkflowName().isBlank()
                && !request.getWorkflowName().equals(workflow.getName())) {
            workflow.setName(request.getWorkflowName().trim());
            workflowMetadataChanged = true;
        }
        if (request.getWorkflowDescription() != null
                && !request.getWorkflowDescription().trim().equals(String.valueOf(workflow.getDescription() == null ? "" : workflow.getDescription()))) {
            workflow.setDescription(request.getWorkflowDescription().trim());
            workflowMetadataChanged = true;
        }
        if (workflowMetadataChanged) {
            workflow.setUpdatedAt(LocalDateTime.now());
            workflow = workflowRepository.save(workflow);
        }
        accessControlService.requireWorkflowAdminAction(userId, workflow.getWorkspaceId(), workflowCode, "workflow.version.create");

        WorkflowVersion version = workflowVersionRepository.findByWorkflowCodeAndVersion(workflowCode, request.getVersion())
                .orElseGet(WorkflowVersion::new);
        String normalizedDefinitionJson = normalizeDefinitionJsonForPersist(request.getDefinition());
        String normalizedConfigJson = normalizeConfigJsonForPersist(request.getConfig());
        String resolvedEntryRuleJson = resolveEntryRuleForPersist(request.getEntryRule(), workflowCode, workflow.getName());
        log.info(
                "workflow.draft.normalized workflowCode={} version={} definitionLength={} entryRuleLength={} metadataChanged={}",
                workflowCode,
                request.getVersion(),
                normalizedDefinitionJson == null ? 0 : normalizedDefinitionJson.length(),
                resolvedEntryRuleJson == null ? 0 : resolvedEntryRuleJson.length(),
                workflowMetadataChanged
        );
        version.setWorkflowCode(workflowCode);
        version.setVersion(request.getVersion());
        version.setDefinition(normalizedDefinitionJson);
        version.setEntryRule(resolvedEntryRuleJson);
        version.setEditorMeta(request.getEditorMeta());
        version.setConfig(normalizedConfigJson);
        version.setWorkflowSnapshot(resolveWorkflowSnapshotForPersist(workflow, request, normalizedDefinitionJson, resolvedEntryRuleJson, normalizedConfigJson));
        version.setStatus(WorkflowVersionStatus.DRAFT);
        version.setCreatedBy(userId);
        if (version.getCreatedAt() == null) {
            version.setCreatedAt(LocalDateTime.now());
        }
        WorkflowVersion saved = workflowVersionRepository.save(version);
        log.info(
                "workflow.draft.persisted workflowCode={} version={} status={} snapshotLength={} configLength={}",
                workflowCode,
                saved.getVersion(),
                saved.getStatus(),
                saved.getWorkflowSnapshot() == null ? 0 : saved.getWorkflowSnapshot().length(),
                saved.getConfig() == null ? 0 : saved.getConfig().length()
        );
        auditService.logAction(workflow.getWorkspaceId(), userId, "workflow.version.save_draft", "workflow_version", workflowCode + ":" + request.getVersion(), request, ApplicationConstants.HTTP_STATUS_OK);
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
                ApplicationConstants.HTTP_STATUS_OK
        );
        return WorkflowVersionResponse.fromEntity(savedVersion, workflow);
    }

    public void deleteWorkflowVersion(String userId, String workflowCode, String version) {
        Workflow workflow = workflowRepository.findByWorkflowCode(workflowCode)
                .orElseThrow(() -> new RuntimeException("Workflow not found: " + workflowCode));
        accessControlService.requireWorkflowAdminAction(userId, workflow.getWorkspaceId(), workflowCode, "workflow.version.delete");

        WorkflowVersion workflowVersion = workflowVersionRepository.findByWorkflowCodeAndVersion(workflowCode, version)
                .orElseThrow(() -> new RuntimeException("Workflow version not found: " + workflowCode + "@" + version));

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

        workflowVersionRepository.delete(workflowVersion);
        auditService.logAction(
                workflow.getWorkspaceId(),
                userId,
                "workflow.version.delete",
                "workflow_version",
                workflowCode + ":" + version,
                null,
                ApplicationConstants.HTTP_STATUS_OK
        );
    }

    private String resolveWorkflowName(CreateWorkflowVersionRequest request, String workflowCode) {
        if (request.getWorkflowName() != null && !request.getWorkflowName().isBlank()) {
            return request.getWorkflowName().trim();
        }
        return workflowCode;
    }

    private String resolveWorkflowDescription(CreateWorkflowVersionRequest request) {
        if (request.getWorkflowDescription() != null) {
            return request.getWorkflowDescription().trim();
        }
        Map<String, Object> definition = parseJsonObject(request.getDefinition());
        String description = stringValue(definition.get("workflow_description"));
        return description == null ? "" : description;
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
            if (type == null || !Set.of("start", "coordinate", "sub_agent", "tool", "api", "message", "end").contains(type)) {
                issues.add(issue(nodeId, "type", "节点类型不受支持"));
                continue;
            }
            if (("coordinate".equals(type) || "sub_agent".equals(type))
                    && stringValue(nodeConfig.get("prompt")) == null
                    && stringValue(nodeConfig.get("user_prompt")) == null) {
                issues.add(issue(nodeId, "config.prompt", "决策节点缺少 prompt"));
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
            if ("tool".equals(type) || "api".equals(type)) {
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
        return routeMessage(content, activeExecution, null, null);
    }

    public RoutingDecision routeMessage(String content, Execution activeExecution, String sessionId, String userId) {
        log.info(
                "workflow.route.start contentLength={} contentPreview={} activeExecutionId={} activeWorkflowCode={} sessionId={}",
                content == null ? 0 : content.length(),
                preview(content),
                activeExecution == null ? null : activeExecution.getId(),
                activeExecution == null ? null : activeExecution.getWorkflowCode(),
                sessionId
        );
        List<WorkflowVersion> versions = resolveCurrentWorkflowVersions();
        if (versions.isEmpty()) {
            versions = filterVisibleWorkflowVersions(
                    workflowVersionRepository.findByStatusOrderByCreatedAtDesc(WorkflowVersionStatus.DRAFT)
            );
        }
        if (versions.isEmpty()) {
            throw new RuntimeException("No workflow versions available");
        }

        String normalizedContent = normalizeText(content);
        List<Map<String, Object>> workflowDefinitions = versions.stream()
                .map(version -> attachWorkflowConfig(
                        normalizeWorkflowDefinition(parseJsonObject(version.getDefinition())),
                        normalizeWorkflowConfig(parseJsonObject(version.getConfig()))
                ))
                .toList();
        String routingModelCode = modelConfigService.resolveRoutingModelCode(workflowDefinitions);
        ModelConfigService.RuntimeModelBundle runtimeBundle = modelConfigService.buildRuntimeBundle(workflowDefinitions, routingModelCode);
        log.info(
                "workflow.route.context versionCount={} definitionCount={} routingModelCode={} providerCount={} modelRecordCount={}",
                versions.size(),
                workflowDefinitions.size(),
                routingModelCode,
                runtimeBundle.providerConfigs().size(),
                runtimeBundle.modelRecords().size()
        );
        List<RoutingDecision.IntentCandidate> regexCandidates = collectRegexCandidates(versions, normalizedContent);
        log.info("workflow.route.regex candidates={}", regexCandidates.size());
        if (!regexCandidates.isEmpty()) {
            return buildAcceptedRoutingDecision(
                    regexCandidates,
                    versions,
                    activeExecution,
                    "regex_match",
                    regexAcceptThreshold(),
                    "regex_accept_threshold"
            );
        }

        List<RoutingDecision.IntentCandidate> phraseCandidates = collectPhraseCandidates(versions, normalizedContent);
        log.info(
                "workflow.route.phrase candidates={} topWorkflowCode={} topEvidence={}",
                phraseCandidates.size(),
                phraseCandidates.isEmpty() ? null : phraseCandidates.get(0).targetCode(),
                phraseCandidates.isEmpty() ? null : phraseCandidates.get(0).evidence()
        );
        if (!phraseCandidates.isEmpty()) {
            return buildAcceptedRoutingDecision(
                    phraseCandidates,
                    versions,
                    activeExecution,
                    "phrase_match",
                    phraseAcceptThreshold(),
                    "phrase_accept_threshold"
            );
        }

        List<RoutingDecision.IntentCandidate> ragCandidates = collectRagCandidates(versions, normalizedContent);
        log.info(
                "workflow.route.rag candidates={} topWorkflowCode={} topConfidence={} topEvidence={} threshold={}",
                ragCandidates.size(),
                ragCandidates.isEmpty() ? null : ragCandidates.get(0).targetCode(),
                ragCandidates.isEmpty() ? null : ragCandidates.get(0).confidence(),
                ragCandidates.isEmpty() ? null : ragCandidates.get(0).evidence(),
                ragAcceptThreshold()
        );
        if (!ragCandidates.isEmpty() && ragCandidates.get(0).confidence() >= ragAcceptThreshold()) {
            return buildAcceptedRoutingDecision(
                    ragCandidates,
                    versions,
                    activeExecution,
                    "rag_match",
                    ragAcceptThreshold(),
                    "rag_accept_threshold"
            );
        }
        if (isSingleRagCandidateAcceptable(ragCandidates)) {
            return buildAcceptedRoutingDecision(
                    ragCandidates,
                    versions,
                    activeExecution,
                    "rag_single_candidate_match",
                    singleRagAcceptThreshold(),
                    "rag_single_candidate_threshold"
            );
        }

        RuntimeRoutingModel runtimeRoutingModel = resolveRuntimeRoutingModel(routingModelCode, runtimeBundle);
        ModelIntent modelIntent = classifyIntent(
                normalizedContent,
                versions,
                runtimeRoutingModel.routingModelCode(),
                runtimeRoutingModel.runtimeBundle(),
                ragCandidates
        );
        log.info(
                "workflow.route.llm_result matched={} workflowCode={} targetType={} confidence={} reason={}",
                modelIntent.matched(),
                modelIntent.workflowCode(),
                modelIntent.targetType(),
                modelIntent.confidence(),
                modelIntent.reason()
        );
        RoutingDecision knowledgeDecision = tryBuildKnowledgeRoutingDecision(
                normalizedContent,
                activeExecution,
                sessionId,
                userId,
                modelIntent,
                ragCandidates,
                versions
        );
        if (knowledgeDecision != null) {
            return knowledgeDecision;
        }
        return buildLlmRoutingDecision(modelIntent, ragCandidates, versions, activeExecution, normalizedContent);
    }

    private RuntimeRoutingModel resolveRuntimeRoutingModel(
            String routingModelCode,
            ModelConfigService.RuntimeModelBundle runtimeBundle
    ) {
        if (!runtimeBundle.providerConfigs().isEmpty() && !runtimeBundle.modelRecords().isEmpty()) {
            return new RuntimeRoutingModel(routingModelCode, runtimeBundle);
        }
        ModelConfigService.RuntimeModelBundle defaultRuntimeBundle = modelConfigService.buildDefaultRuntimeBundle();
        if (defaultRuntimeBundle == null) {
            return new RuntimeRoutingModel(routingModelCode, runtimeBundle);
        }
        String defaultModelCode = firstRuntimeModelCode(defaultRuntimeBundle.modelRecords());
        if (defaultModelCode != null) {
            log.info(
                    "workflow.intent.classify.model.fallback reason=routing_model_unavailable routingModelCode={} defaultModelCode={}",
                    routingModelCode,
                    defaultModelCode
            );
            return new RuntimeRoutingModel(defaultModelCode, defaultRuntimeBundle);
        }
        return new RuntimeRoutingModel(routingModelCode, runtimeBundle);
    }

    private RoutingDecision buildAcceptedRoutingDecision(
            List<RoutingDecision.IntentCandidate> candidates,
            List<WorkflowVersion> versions,
            Execution activeExecution,
            String reason,
            double threshold,
            String thresholdSource
    ) {
        RoutingDecision.IntentCandidate primary = candidates.get(0);
        WorkflowVersion selected = selectWorkflowVersion(versions, primary.targetCode());
        String decision = "start";
        String finalReason = reason;
        if (activeExecution != null
                && !activeExecution.getStatus().isTerminal()
                && activeExecution.getStatus() != ExecutionStatus.SUSPENDED
                && selected != null
                && !activeExecution.getWorkflowCode().equals(selected.getWorkflowCode())) {
            decision = "switch_required";
            finalReason = "active_execution_conflict";
        }
        log.info(
                "workflow.route.accept decision={} workflowCode={} workflowVersion={} source={} confidence={} threshold={} evidence={} candidateCount={}",
                decision,
                selected == null ? primary.targetCode() : selected.getWorkflowCode(),
                selected == null ? null : selected.getVersion(),
                reason,
                primary.confidence(),
                threshold,
                primary.evidence(),
                candidates.size()
        );
        return new RoutingDecision(
                decision,
                selected == null ? primary.targetCode() : selected.getWorkflowCode(),
                selected == null ? null : selected.getVersion(),
                primary.confidence(),
                threshold,
                thresholdSource,
                finalReason,
                topCandidateWorkflowCodes(candidates, 3),
                selected == null ? 0 : extractPriority(selected.getEntryRule()),
                primary.intentCode(),
                primary.targetType(),
                primary.targetCode(),
                null,
                secondaryCandidates(candidates)
        );
    }

    private RoutingDecision buildLlmRoutingDecision(
            ModelIntent modelIntent,
            List<RoutingDecision.IntentCandidate> ragCandidates,
            List<WorkflowVersion> versions,
            Execution activeExecution,
            String normalizedContent
    ) {
        if (!modelIntent.matched()) {
            return new RoutingDecision(
                    "clarification_required",
                    null,
                    null,
                    Math.max(modelIntent.confidence(), 0.0d),
                    llmAcceptThreshold(),
                    "llm_accept_threshold",
                    "llm_no_match",
                    topCandidateWorkflowCodes(ragCandidates, 3),
                    0,
                    null,
                    modelIntent.targetType(),
                    null,
                    modelFallbackQuestion(modelIntent.clarificationQuestion()),
                    ragCandidates
            );
        }

        WorkflowVersion matchedVersion = selectWorkflowVersion(versions, modelIntent.workflowCode());
        if (matchedVersion == null) {
            log.info(
                    "workflow.route.llm_target_missing workflowCode={} intentCode={} contentLength={}",
                    modelIntent.workflowCode(),
                    modelIntent.intentCode(),
                    normalizedContent == null ? 0 : normalizedContent.length()
            );
            return new RoutingDecision(
                    "clarification_required",
                    null,
                    null,
                    modelIntent.confidence(),
                    llmAcceptThreshold(),
                    "llm_accept_threshold",
                    "llm_target_workflow_missing",
                    topCandidateWorkflowCodes(ragCandidates, 3),
                    0,
                    modelIntent.intentCode(),
                    modelIntent.targetType(),
                    modelIntent.workflowCode(),
                    modelFallbackQuestion(modelIntent.clarificationQuestion()),
                    ragCandidates
            );
        }

        RoutingDecision.IntentCandidate llmPrimary = new RoutingDecision.IntentCandidate(
                firstNonBlank(modelIntent.intentCode(), modelIntent.workflowCode()),
                firstNonBlank(modelIntent.targetType(), "workflow"),
                modelIntent.workflowCode(),
                modelIntent.confidence(),
                "llm",
                firstNonBlank(modelIntent.reason(), "llm_match")
        );

        List<RoutingDecision.IntentCandidate> mergedCandidates = new ArrayList<>();
        mergedCandidates.add(llmPrimary);
        for (RoutingDecision.IntentCandidate candidate : ragCandidates) {
            if (!Objects.equals(candidate.targetCode(), llmPrimary.targetCode())) {
                mergedCandidates.add(candidate);
            }
        }
        return buildAcceptedRoutingDecision(
                mergedCandidates,
                versions,
                activeExecution,
                "llm_match",
                llmAcceptThreshold(),
                "llm_accept_threshold"
        );
    }

    private RoutingDecision tryBuildKnowledgeRoutingDecision(
            String normalizedContent,
            Execution activeExecution,
            String sessionId,
            String userId,
            ModelIntent modelIntent,
            List<RoutingDecision.IntentCandidate> ragCandidates,
            List<WorkflowVersion> versions
    ) {
        if (knowledgeBindingService == null
                || knowledgeRouteDecisionService == null
                || knowledgeService == null
                || sessionId == null
                || sessionId.isBlank()) {
            return null;
        }
        if (modelIntent != null && modelIntent.confidence() >= knowledgeRouteDecisionService.intentPrimaryThreshold()) {
            log.info(
                    "workflow.knowledge.route.skip reason=intent_primary sessionId={} intentConfidence={}",
                    sessionId,
                    modelIntent.confidence()
            );
            return buildLlmRoutingDecision(modelIntent, ragCandidates, versions, activeExecution, normalizedContent);
        }
        List<String> boundKbCodes = boundKnowledgeBaseCodes(sessionId, activeExecution);
        if (boundKbCodes.isEmpty()) {
            log.info("workflow.knowledge.route.skip reason=no_bound_knowledge sessionId={}", sessionId);
            return null;
        }

        KnowledgeSearchResponse knowledgeResponse;
        try {
            KnowledgeSearchRequest request = new KnowledgeSearchRequest();
            request.setQuery(normalizedContent);
            request.setKbCodes(boundKbCodes);
            request.setRetrievalMode("hybrid");
            request.setTopK(5);
            request.setGenerateAnswer(false);
            knowledgeResponse = knowledgeService.searchKnowledge(userId, request);
        } catch (RuntimeException exception) {
            log.warn(
                    "workflow.knowledge.route.search_failed sessionId={} kbCodes={} message={}",
                    sessionId,
                    boundKbCodes,
                    exception.getMessage()
            );
            return null;
        }

        double knowledgeBestScore = knowledgeResponse == null || knowledgeResponse.getBestScore() == null
                ? 0.0d
                : knowledgeResponse.getBestScore();
        KnowledgeRouteDecisionService.Decision routeDecision = knowledgeRouteDecisionService.decide(
                modelIntent == null ? 0.0d : modelIntent.confidence(),
                knowledgeBestScore
        );
        log.info(
                "workflow.knowledge.route.decision sessionId={} finalRoute={} reason={} intentConfidence={} knowledgeBestScore={} kbCount={}",
                sessionId,
                routeDecision.finalRoute(),
                routeDecision.routeReason(),
                modelIntent == null ? 0.0d : modelIntent.confidence(),
                knowledgeBestScore,
                boundKbCodes.size()
        );

        if ("INTENT".equalsIgnoreCase(routeDecision.finalRoute())) {
            return buildLlmRoutingDecision(modelIntent, ragCandidates, versions, activeExecution, normalizedContent);
        }
        if ("KNOWLEDGE".equalsIgnoreCase(routeDecision.finalRoute())) {
            return buildKnowledgeAnswerRoutingDecision(knowledgeResponse, knowledgeBestScore, routeDecision.routeReason());
        }
        if ("CLARIFY".equalsIgnoreCase(routeDecision.finalRoute())) {
            return buildKnowledgeClarificationRoutingDecision(modelIntent, ragCandidates, knowledgeResponse, knowledgeBestScore, routeDecision.routeReason());
        }
        return null;
    }

    private List<String> boundKnowledgeBaseCodes(String sessionId, Execution activeExecution) {
        List<KnowledgeBinding> bindings = new ArrayList<>(knowledgeBindingService.getBindings(KnowledgeBindingScope.SESSION, sessionId));
        if (activeExecution != null
                && activeExecution.getWorkflowCode() != null
                && !activeExecution.getWorkflowCode().isBlank()) {
            bindings.addAll(knowledgeBindingService.getBindings(KnowledgeBindingScope.WORKFLOW, activeExecution.getWorkflowCode()));
        }
        return bindings
                .stream()
                .filter(KnowledgeBinding::isEnabled)
                .map(KnowledgeBinding::getKbCode)
                .filter(code -> code != null && !code.isBlank())
                .distinct()
                .toList();
    }

    private RoutingDecision buildKnowledgeAnswerRoutingDecision(
            KnowledgeSearchResponse knowledgeResponse,
            double knowledgeBestScore,
            String routeReason
    ) {
        return new RoutingDecision(
                "knowledge_answer",
                null,
                null,
                knowledgeBestScore,
                knowledgeRouteDecisionService.knowledgePrimaryThreshold(),
                "knowledge_primary_threshold",
                routeReason,
                List.of(),
                0,
                null,
                "knowledge",
                null,
                firstNonBlank(knowledgeResponse == null ? null : knowledgeResponse.getAnswer(), topKnowledgeContent(knowledgeResponse)),
                List.of()
        );
    }

    private RoutingDecision buildKnowledgeClarificationRoutingDecision(
            ModelIntent modelIntent,
            List<RoutingDecision.IntentCandidate> ragCandidates,
            KnowledgeSearchResponse knowledgeResponse,
            double knowledgeBestScore,
            String routeReason
    ) {
        return new RoutingDecision(
                "clarification_required",
                null,
                null,
                Math.max(modelIntent == null ? 0.0d : modelIntent.confidence(), knowledgeBestScore),
                knowledgeRouteDecisionService.knowledgeClarifyThreshold(),
                "knowledge_clarify_threshold",
                routeReason,
                topCandidateWorkflowCodes(ragCandidates, 3),
                0,
                modelIntent == null ? null : modelIntent.intentCode(),
                modelIntent == null ? "knowledge" : modelIntent.targetType(),
                modelIntent == null ? null : modelIntent.workflowCode(),
                firstNonBlank(
                        modelIntent == null ? null : modelIntent.clarificationQuestion(),
                        knowledgeResponse == null ? null : knowledgeResponse.getAnswer(),
                        "我还不确定你想办理业务流程，还是查询知识库内容，可以再具体说明一下吗？"
                ),
                ragCandidates
        );
    }

    private String topKnowledgeContent(KnowledgeSearchResponse knowledgeResponse) {
        if (knowledgeResponse == null || knowledgeResponse.getDocuments() == null || knowledgeResponse.getDocuments().isEmpty()) {
            return null;
        }
        return knowledgeResponse.getDocuments().stream()
                .map(KnowledgeSearchResponse.DocumentHit::getContent)
                .filter(content -> content != null && !content.isBlank())
                .findFirst()
                .orElse(null);
    }

    private List<RoutingDecision.IntentCandidate> collectRegexCandidates(
            List<WorkflowVersion> versions,
            String normalizedContent
    ) {
        if (normalizedContent.isBlank()) {
            return List.of();
        }
        List<RegexCandidate> regexCandidates = new ArrayList<>();
        for (WorkflowVersion version : versions) {
            try {
                JsonNode entryRule = readJsonObject(version.getEntryRule());
                List<String> intentCodes = readStringArray(entryRule.path("intent_codes"));
                List<String> keywords = readStringArray(entryRule.path("keywords"));
                List<String> regexPatterns = readStringArray(entryRule.path("regex_patterns"));
                List<String> evidence = new ArrayList<>();
                for (String keyword : keywords) {
                    if (!keyword.isBlank() && normalizedContent.contains(normalizeText(keyword))) {
                        evidence.add("keyword:" + keyword);
                    }
                }
                for (String intentCode : intentCodes) {
                    if (!intentCode.isBlank() && normalizedContent.contains(normalizeText(intentCode))) {
                        evidence.add("intent_code:" + intentCode);
                    }
                }
                for (String regexPattern : regexPatterns) {
                    if (regexPattern == null || regexPattern.isBlank()) {
                        continue;
                    }
                    try {
                        if (java.util.regex.Pattern.compile(regexPattern, java.util.regex.Pattern.CASE_INSENSITIVE)
                                .matcher(normalizedContent)
                                .find()) {
                            evidence.add("regex:" + regexPattern);
                        }
                    } catch (Exception ignored) {
                    }
                }
                if (!evidence.isEmpty()) {
                    String intentCode = intentCodes.isEmpty() ? version.getWorkflowCode() : intentCodes.get(0);
                    RoutingDecision.IntentCandidate candidate = new RoutingDecision.IntentCandidate(
                            intentCode,
                            "workflow",
                            version.getWorkflowCode(),
                            1.0d,
                            "regex",
                            String.join("; ", evidence)
                    );
                    regexCandidates.add(new RegexCandidate(candidate, evidence.size(), extractPriority(version.getEntryRule())));
                }
            } catch (Exception ignored) {
            }
        }
        return regexCandidates.stream()
                .sorted(Comparator.comparingInt(RegexCandidate::evidenceCount).reversed()
                        .thenComparing(Comparator.comparingInt(RegexCandidate::priority).reversed())
                        .thenComparing(item -> item.candidate().targetCode()))
                .map(RegexCandidate::candidate)
                .collect(Collectors.toMap(
                        RoutingDecision.IntentCandidate::targetCode,
                        candidate -> candidate,
                        (left, right) -> left,
                        LinkedHashMap::new
                ))
                .values()
                .stream()
                .toList();
    }

    private List<RoutingDecision.IntentCandidate> collectPhraseCandidates(
            List<WorkflowVersion> versions,
            String normalizedContent
    ) {
        if (normalizedContent.isBlank()) {
            return List.of();
        }
        String comparableContent = normalizeRoutePhrase(normalizedContent);
        Map<String, Workflow> workflowsByCode = new HashMap<>();
        for (WorkflowVersion version : versions) {
            String workflowCode = version.getWorkflowCode();
            if (workflowCode == null || workflowsByCode.containsKey(workflowCode)) {
                continue;
            }
            workflowsByCode.put(workflowCode, workflowRepository.findByWorkflowCode(workflowCode).orElse(null));
        }

        List<RegexCandidate> phraseCandidates = new ArrayList<>();
        for (WorkflowVersion version : versions) {
            Workflow workflow = workflowsByCode.get(version.getWorkflowCode());
            List<String> phrases = collectDeterministicPhrases(version, workflow);
            List<String> evidence = new ArrayList<>();
            for (String phrase : phrases) {
                String comparablePhrase = normalizeRoutePhrase(phrase);
                if (isStrongRoutePhrase(comparablePhrase) && routePhraseMatches(comparableContent, comparablePhrase)) {
                    evidence.add("phrase:" + phrase);
                }
            }
            if (!evidence.isEmpty()) {
                JsonNode entryRule = parseEntryRuleJson(version.getEntryRule());
                List<String> intentCodes = readStringArray(entryRule.path("intent_codes"));
                RoutingDecision.IntentCandidate candidate = new RoutingDecision.IntentCandidate(
                        intentCodes.isEmpty() ? version.getWorkflowCode() : intentCodes.get(0),
                        "workflow",
                        version.getWorkflowCode(),
                        1.0d,
                        "phrase",
                        String.join("; ", evidence)
                );
                phraseCandidates.add(new RegexCandidate(candidate, evidence.size(), extractPriority(version.getEntryRule())));
            }
        }
        return phraseCandidates.stream()
                .sorted(Comparator.comparingInt(RegexCandidate::evidenceCount).reversed()
                        .thenComparing(Comparator.comparingInt(RegexCandidate::priority).reversed())
                        .thenComparing(item -> item.candidate().targetCode()))
                .map(RegexCandidate::candidate)
                .collect(Collectors.toMap(
                        RoutingDecision.IntentCandidate::targetCode,
                        candidate -> candidate,
                        (left, right) -> left,
                        LinkedHashMap::new
                ))
                .values()
                .stream()
                .toList();
    }

    private List<String> collectDeterministicPhrases(WorkflowVersion version, Workflow workflow) {
        List<String> phrases = new ArrayList<>();
        if (workflow != null) {
            if (workflow.getName() != null) {
                phrases.add(workflow.getName());
            }
            if (workflow.getDescription() != null) {
                phrases.add(workflow.getDescription());
            }
        }
        JsonNode entryRule = parseEntryRuleJson(version.getEntryRule());
        phrases.addAll(readStringArray(entryRule.path("keywords")));
        phrases.addAll(readStringArray(entryRule.path("examples")));
        return phrases.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private boolean isStrongRoutePhrase(String phrase) {
        return phrase != null && phrase.length() >= 4;
    }

    private boolean routePhraseMatches(String comparableContent, String comparablePhrase) {
        if (comparableContent.contains(comparablePhrase)) {
            return true;
        }
        List<String> phraseChunks = cjkPhraseChunks(comparablePhrase);
        if (phraseChunks.size() < 2) {
            return false;
        }
        return phraseChunks.stream().allMatch(comparableContent::contains);
    }

    private List<String> cjkPhraseChunks(String phrase) {
        if (phrase == null || phrase.isBlank()) {
            return List.of();
        }
        StringBuilder cjkOnly = new StringBuilder();
        for (int index = 0; index < phrase.length(); index++) {
            char ch = phrase.charAt(index);
            if (isCjk(ch)) {
                cjkOnly.append(ch);
            }
        }
        if (cjkOnly.length() < 4 || cjkOnly.length() % 2 != 0) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        for (int index = 0; index < cjkOnly.length(); index += 2) {
            chunks.add(cjkOnly.substring(index, index + 2));
        }
        return chunks;
    }

    private String normalizeRoutePhrase(String value) {
        if (value == null) {
            return "";
        }
        return normalizeText(value);
    }

    private List<RoutingDecision.IntentCandidate> collectRagCandidates(
            List<WorkflowVersion> versions,
            String normalizedContent
    ) {
        if (normalizedContent.isBlank()) {
            return List.of();
        }
        Set<String> messageTokens = tokenize(normalizedContent);
        if (messageTokens.isEmpty()) {
            return List.of();
        }
        Map<String, Workflow> workflowsByCode = new HashMap<>();
        for (WorkflowVersion version : versions) {
            String workflowCode = version.getWorkflowCode();
            if (workflowCode == null || workflowsByCode.containsKey(workflowCode)) {
                continue;
            }
            Workflow workflow = workflowRepository.findByWorkflowCode(workflowCode).orElse(null);
            workflowsByCode.put(workflowCode, workflow);
        }
        List<RagCandidate> scored = new ArrayList<>();
        for (WorkflowVersion version : versions) {
            Workflow workflow = workflowsByCode.get(version.getWorkflowCode());
            List<String> phrases = collectRagPhrases(version, workflow);
            if (phrases.isEmpty()) {
                continue;
            }
            Set<String> phraseTokens = new LinkedHashSet<>();
            for (String phrase : phrases) {
                phraseTokens.addAll(tokenize(phrase));
            }
            if (phraseTokens.isEmpty()) {
                continue;
            }
            Set<String> overlap = new LinkedHashSet<>(messageTokens);
            overlap.retainAll(phraseTokens);
            int phraseHits = 0;
            String bestEvidence = null;
            for (String phrase : phrases) {
                String normalizedPhrase = normalizeText(phrase);
                if (!normalizedPhrase.isBlank() && normalizedContent.contains(normalizedPhrase)) {
                    phraseHits++;
                    if (bestEvidence == null) {
                        bestEvidence = phrase;
                    }
                }
            }
            double overlapScore = (double) overlap.size() / (double) messageTokens.size();
            double phraseScore = phrases.isEmpty() ? 0.0d : (double) phraseHits / (double) phrases.size();
            double confidence = Math.max(0.0d, Math.min(1.0d, overlapScore * 0.8d + phraseScore * 0.2d));
            if (bestEvidence != null) {
                String normalizedEvidence = normalizeText(bestEvidence);
                if (!normalizedEvidence.isBlank()
                        && normalizedEvidence.length() >= 4
                        && normalizedContent.contains(normalizedEvidence)) {
                    confidence = Math.min(1.0d, confidence + 0.2d);
                }
            }
            if (confidence <= 0.0d) {
                continue;
            }
            JsonNode entryRule = parseEntryRuleJson(version.getEntryRule());
            List<String> intentCodes = readStringArray(entryRule.path("intent_codes"));
            RoutingDecision.IntentCandidate candidate = new RoutingDecision.IntentCandidate(
                    intentCodes.isEmpty() ? version.getWorkflowCode() : intentCodes.get(0),
                    "workflow",
                    version.getWorkflowCode(),
                    confidence,
                    "rag",
                    firstNonBlank(bestEvidence, overlap.isEmpty() ? null : String.join(",", overlap))
            );
            scored.add(new RagCandidate(candidate, extractPriority(version.getEntryRule())));
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble((RagCandidate item) -> item.candidate().confidence()).reversed()
                        .thenComparing(Comparator.comparingInt(RagCandidate::priority).reversed())
                        .thenComparing(item -> item.candidate().targetCode()))
                .map(RagCandidate::candidate)
                .collect(Collectors.toMap(
                        RoutingDecision.IntentCandidate::targetCode,
                        candidate -> candidate,
                        (left, right) -> left,
                        LinkedHashMap::new
                ))
                .values()
                .stream()
                .limit(ragTopK())
                .toList();
    }

    private JsonNode parseEntryRuleJson(String entryRuleJson) {
        try {
            return readJsonObject(entryRuleJson);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private List<String> collectRagPhrases(WorkflowVersion version, Workflow workflow) {
        List<String> phrases = new ArrayList<>();
        phrases.add(version.getWorkflowCode());
        if (workflow != null) {
            if (workflow.getName() != null) {
                phrases.add(workflow.getName());
            }
            if (workflow.getDescription() != null) {
                phrases.add(workflow.getDescription());
            }
        }
        JsonNode entryRule = parseEntryRuleJson(version.getEntryRule());
        phrases.addAll(readStringArray(entryRule.path("intent_codes")));
        phrases.addAll(readStringArray(entryRule.path("keywords")));
        phrases.addAll(readStringArray(entryRule.path("examples")));
        return phrases.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private List<String> readStringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && item.isTextual()) {
                String value = item.asText("");
                if (!value.isBlank()) {
                    values.add(value.trim());
                }
            }
        }
        return values;
    }

    private Set<String> tokenize(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        Arrays.stream(normalized.split("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fff]+"))
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .forEach(tokens::add);
        StringBuilder cjkBlock = new StringBuilder();
        for (int index = 0; index < normalized.length(); index++) {
            char ch = normalized.charAt(index);
            if (isCjk(ch)) {
                cjkBlock.append(ch);
            } else if (!cjkBlock.isEmpty()) {
                collectCjkTokens(cjkBlock.toString(), tokens);
                cjkBlock.setLength(0);
            }
        }
        if (!cjkBlock.isEmpty()) {
            collectCjkTokens(cjkBlock.toString(), tokens);
        }
        return tokens;
    }

    private void collectCjkTokens(String text, Set<String> tokens) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (int index = 0; index < text.length(); index++) {
            tokens.add(String.valueOf(text.charAt(index)));
            if (index + 1 < text.length()) {
                tokens.add(text.substring(index, index + 2));
            }
        }
    }

    private boolean isCjk(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    private String normalizeText(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).trim();
    }

    private String preview(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80) + "...";
    }

    private int ragTopK() {
        return 5;
    }

    private double regexAcceptThreshold() {
        return workflowRoutingProperties.getRegexAcceptThreshold();
    }

    private double phraseAcceptThreshold() {
        return workflowRoutingProperties.getPhraseAcceptThreshold();
    }

    private double ragAcceptThreshold() {
        return workflowRoutingProperties.getRagAcceptThreshold();
    }

    private double singleRagAcceptThreshold() {
        return workflowRoutingProperties.getSingleRagAcceptThreshold();
    }

    private boolean isSingleRagCandidateAcceptable(List<RoutingDecision.IntentCandidate> ragCandidates) {
        return ragCandidates.size() == 1
                && ragCandidates.get(0).confidence() >= singleRagAcceptThreshold();
    }

    private double llmAcceptThreshold() {
        return workflowRoutingProperties.getLlmAcceptThreshold();
    }

    private String defaultClarificationQuestion(String question) {
        return firstNonBlank(question, "我还不确定你想办理什么业务，可以再具体说明一下吗？");
    }

    private String modelFallbackQuestion(String modelQuestion) {
        return firstNonBlank(modelQuestion, "抱歉，当前没有匹配的可用服务，您还需要其他服务吗？");
    }

    private List<String> topCandidateWorkflowCodes(List<RoutingDecision.IntentCandidate> candidates, int limit) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
                .map(RoutingDecision.IntentCandidate::targetCode)
                .filter(Objects::nonNull)
                .distinct()
                .limit(Math.max(1, limit))
                .toList();
    }

    private List<RoutingDecision.IntentCandidate> secondaryCandidates(List<RoutingDecision.IntentCandidate> candidates) {
        if (candidates == null || candidates.size() <= 1) {
            return List.of();
        }
        return candidates.subList(1, candidates.size())
                .stream()
                .filter(candidate -> candidate != null && candidate.targetCode() != null)
                .toList();
    }

    private WorkflowVersion selectWorkflowVersion(List<WorkflowVersion> versions, String workflowCode) {
        return versions.stream()
                .filter(version -> Objects.equals(version.getWorkflowCode(), workflowCode))
                .findFirst()
                .orElse(null);
    }

    private List<WorkflowVersion> resolveCurrentWorkflowVersions() {
        List<Workflow> workflows = workflowRepository.findByStatusOrderByCreatedAtDesc(WorkflowStatus.PUBLISHED);
        List<WorkflowVersion> currentVersions = new ArrayList<>();
        for (Workflow workflow : workflows) {
            if (!isVisibleWorkflow(workflow)) {
                continue;
            }
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
        if (runtimeBundle.providerConfigs().isEmpty() || runtimeBundle.modelRecords().isEmpty()) {
            ModelConfigService.RuntimeModelBundle defaultRuntimeBundle = modelConfigService.buildDefaultRuntimeBundle();
            String defaultModelCode = firstRuntimeModelCode(defaultRuntimeBundle.modelRecords());
            if (defaultModelCode != null) {
                log.info(
                        "workflow.runtime.model.fallback workflowCode={} version={} reason=model_binding_unavailable routingModelCode={} defaultModelCode={}",
                        workflowCode,
                        version,
                        routingModelCode,
                        defaultModelCode
                );
                runtimeBundle = defaultRuntimeBundle;
                routingModelCode = defaultModelCode;
                normalizedWorkflowConfig = withDefaultModelBinding(normalizedWorkflowConfig, defaultModelCode);
            } else {
                log.warn(
                        "workflow.runtime.model.missing workflowCode={} version={} reason=no_default_model routingModelCode={} providerCount={} modelRecordCount={}",
                        workflowCode,
                        version,
                        routingModelCode,
                        runtimeBundle.providerConfigs().size(),
                        runtimeBundle.modelRecords().size()
                );
            }
        }
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

    private Map<String, Object> withDefaultModelBinding(Map<String, Object> workflowConfig, String modelCode) {
        Map<String, Object> updated = new LinkedHashMap<>(workflowConfig == null ? Map.of() : workflowConfig);
        updated.put("routing_model_code", modelCode);

        Map<String, Object> llmDefaults = new LinkedHashMap<>(asMap(updated.get("llm_defaults")));
        llmDefaults.put("model_code", modelCode);
        updated.put("llm_defaults", llmDefaults);

        Map<String, Object> modelBindings = new LinkedHashMap<>(asMap(updated.get("model_bindings")));
        modelBindings.put("routing_model_code", modelCode);
        Map<String, Object> bindingDefaults = new LinkedHashMap<>(asMap(modelBindings.get("llm_defaults")));
        bindingDefaults.put("model_code", modelCode);
        modelBindings.put("llm_defaults", bindingDefaults);
        updated.put("model_bindings", modelBindings);

        return updated;
    }

    private String firstRuntimeModelCode(List<Map<String, Object>> modelRecords) {
        if (modelRecords == null) {
            return null;
        }
        for (Map<String, Object> modelRecord : modelRecords) {
            String modelCode = firstNonBlank(stringValue(modelRecord.get("model_code")));
            if (modelCode != null) {
                return modelCode;
            }
        }
        return null;
    }

    private ModelIntent classifyIntent(
            String normalizedContent,
            List<WorkflowVersion> versions,
            String routingModelCode,
            ModelConfigService.RuntimeModelBundle runtimeBundle,
            List<RoutingDecision.IntentCandidate> ragCandidates
    ) {
        if (runtimeBundle.providerConfigs().isEmpty() || runtimeBundle.modelRecords().isEmpty()) {
            log.warn(
                    "workflow.intent.classify.skip reason=model_config_unavailable providerCount={} modelRecordCount={}",
                    runtimeBundle.providerConfigs().size(),
                    runtimeBundle.modelRecords().size()
            );
            return fallbackModelIntent("model_config_unavailable");
        }
        List<Map<String, Object>> candidates = ragCandidates.stream()
                .limit(ragTopK())
                .map(candidate -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("workflow_code", candidate.targetCode());
                    row.put("target_type", candidate.targetType());
                    row.put("target_code", candidate.targetCode());
                    row.put("confidence", candidate.confidence());
                    row.put("evidence", candidate.evidence());
                    row.put("source", candidate.source());
                    row.put("intent_code", candidate.intentCode());
                    return row;
                })
                .toList();
        ModelConfigService.RuntimeModelBundle classifierRuntimeBundle = runtimeBundleForModel(routingModelCode, runtimeBundle);
        Map<String, Object> response;
        try {
            log.info(
                    "workflow.intent.classify.request routingModelCode={} candidateCount={} providerCount={} modelRecordCount={}",
                    routingModelCode,
                    candidates.size(),
                    classifierRuntimeBundle.providerConfigs().size(),
                    classifierRuntimeBundle.modelRecords().size()
            );
            Map<String, Object> classifyRequest = new LinkedHashMap<>();
            classifyRequest.put("message", normalizedContent);
            classifyRequest.put("routing_model_code", routingModelCode);
            classifyRequest.put("candidate_workflows", candidates);
            classifyRequest.put("provider_configs", classifierRuntimeBundle.providerConfigs());
            classifyRequest.put("model_records", classifierRuntimeBundle.modelRecords());
            classifyRequest.put("system_prompts", workflowPromptProperties.asWorkflowConfigSystemPrompts());
            response = pythonClient.classifyIntent(classifyRequest)
                    .blockOptional()
                    .orElseThrow(() -> new RuntimeException("Intent classification unavailable"));
        } catch (RuntimeException exception) {
            log.warn("workflow.intent.classify.failed routingModelCode={} message={}", routingModelCode, exception.getMessage());
            return fallbackModelIntent("intent_classification_fallback");
        }
        String intentCode = stringValue(response.get("intent_code"));
        String workflowCode = firstNonBlank(
                stringValue(response.get("target_code")),
                stringValue(response.get("workflow_code"))
        );
        String targetType = firstNonBlank(stringValue(response.get("target_type")), "workflow");
        double confidence = Math.max(0.0d, Math.min(1.0d, toDouble(response.get("confidence"), 0.0d)));
        String reason = stringValue(response.get("reason"));
        boolean matched = Boolean.TRUE.equals(response.get("matched"));
        boolean needClarification = Boolean.TRUE.equals(response.get("need_clarification"));
        log.info(
                "workflow.intent.classify.response matched={} workflowCode={} targetType={} confidence={} needClarification={} responseKeys={}",
                matched,
                workflowCode,
                targetType,
                confidence,
                needClarification,
                response.keySet()
        );
        String clarificationQuestion = stringValue(response.get("clarification_question"));
        if (!matched) {
            return new ModelIntent(
                    false,
                    null,
                    null,
                    targetType,
                    confidence,
                    reason,
                    needClarification,
                    clarificationQuestion
            );
        }
        if (workflowCode == null) {
            return fallbackModelIntent("missing_workflow_code");
        }
        return new ModelIntent(
                true,
                intentCode == null ? workflowCode : intentCode,
                workflowCode,
                targetType,
                confidence,
                reason,
                false,
                clarificationQuestion
        );
    }

    private ModelConfigService.RuntimeModelBundle runtimeBundleForModel(
            String modelCode,
            ModelConfigService.RuntimeModelBundle runtimeBundle
    ) {
        if (modelCode == null || modelCode.isBlank()
                || runtimeBundle == null
                || runtimeBundle.modelRecords() == null
                || runtimeBundle.providerConfigs() == null) {
            return runtimeBundle;
        }

        Map<String, Object> selectedModelRecord = runtimeBundle.modelRecords().stream()
                .filter(record -> Objects.equals(modelCode, stringValue(record.get("model_code"))))
                .findFirst()
                .orElse(null);
        if (selectedModelRecord == null) {
            return runtimeBundle;
        }

        String providerCode = stringValue(selectedModelRecord.get("provider_code"));
        if (providerCode == null) {
            return runtimeBundle;
        }

        Map<String, Object> selectedProviderConfig = runtimeBundle.providerConfigs().stream()
                .filter(provider -> Objects.equals(providerCode, stringValue(provider.get("provider_code"))))
                .findFirst()
                .orElse(null);
        if (selectedProviderConfig == null) {
            return runtimeBundle;
        }

        return new ModelConfigService.RuntimeModelBundle(
                List.of(selectedProviderConfig),
                List.of(selectedModelRecord)
        );
    }

    private ModelIntent fallbackModelIntent(String reason) {
        return new ModelIntent(
                false,
                null,
                null,
                "workflow",
                0.0d,
                reason,
                true,
                null
        );
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
        List<WorkflowVersion> versions = filterVisibleWorkflowVersions(
                workflowVersionRepository.findByStatusOrderByCreatedAtDesc(WorkflowVersionStatus.PUBLISHED)
        );
        Map<String, Map<String, Object>> catalog = new LinkedHashMap<>();
        for (WorkflowVersion version : versions) {
            log.debug("workflow.catalog.add workflowCode={} version={}", version.getWorkflowCode(), version.getVersion());
            catalog.put(
                    version.getWorkflowCode() + "@" + version.getVersion(),
                    normalizeWorkflowDefinition(parseJsonObject(version.getDefinition()))
            );
        }
        log.info("workflow.catalog.built publishedVisibleCount={} catalogSize={}", versions.size(), catalog.size());
        return catalog;
    }

    private List<WorkflowVersion> filterVisibleWorkflowVersions(List<WorkflowVersion> versions) {
        if (versions == null) {
            return List.of();
        }
        return versions.stream()
                .filter(version -> isVisibleWorkflowCode(version.getWorkflowCode()))
                .toList();
    }

    private boolean isVisibleWorkflowCode(String workflowCode) {
        if (workflowCode == null || workflowCode.isBlank()) {
            return false;
        }
        Optional<Workflow> workflow = workflowRepository.findByWorkflowCode(workflowCode);
        return workflow != null && workflow.map(this::isVisibleWorkflow).orElse(false);
    }

    private boolean isVisibleWorkflow(Workflow workflow) {
        return workflow != null && workflow.getStatus() != WorkflowStatus.ARCHIVED;
    }

    private boolean isUserManagedWorkflow(Workflow workflow) {
        if (workflow == null || "system".equalsIgnoreCase(workflow.getCreatedBy())) {
            return false;
        }
        String code = workflow.getWorkflowCode() == null ? "" : workflow.getWorkflowCode();
        String description = workflow.getDescription() == null ? "" : workflow.getDescription();
        if ("cap_workflow".equals(code) || code.startsWith("cap_workflow_")) {
            return false;
        }
        if (("workflow_1776609829026".equals(code) || "workflow_1777206095089".equals(code))
                && "Auto-created draft workflow".equals(description)) {
            return false;
        }
        return true;
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

    private String normalizeConfigJsonForPersist(String configJson) {
        Map<String, Object> normalized = normalizeWorkflowConfig(parseJsonObject(configJson));
        return writeJsonObject(normalized);
    }

    private String resolveWorkflowSnapshotForPersist(
            Workflow workflow,
            CreateWorkflowVersionRequest request,
            String normalizedDefinitionJson,
            String entryRuleJson,
            String configJson
    ) {
        String workflowCode = workflow.getWorkflowCode();
        String workflowName = firstNonBlank(
                request.getWorkflowName() == null ? null : request.getWorkflowName().trim(),
                workflow.getName(),
                workflowCode
        );
        String workflowDescription = firstNonBlank(
                request.getWorkflowDescription() == null ? null : request.getWorkflowDescription().trim(),
                workflow.getDescription(),
                ""
        );
        String workflowVersion = request.getVersion();
        if (request.getWorkflowSnapshot() == null || request.getWorkflowSnapshot().isBlank()) {
            return buildCompatibilityWorkflowSnapshot(
                    workflowCode,
                    workflowName,
                    workflowDescription,
                    workflowVersion,
                    normalizedDefinitionJson,
                    entryRuleJson,
                    request.getEditorMeta(),
                    configJson
            );
        }
        return normalizeProvidedWorkflowSnapshot(
                request.getWorkflowSnapshot(),
                workflowCode,
                workflowName,
                workflowDescription,
                workflowVersion,
                entryRuleJson,
                request.getEditorMeta(),
                configJson
        );
    }

    private String resolveEntryRuleForPersist(String entryRuleJson, String workflowCode, String workflowName) {
        Map<String, Object> entryRule = parseJsonObject(entryRuleJson);
        if (entryRule.isEmpty()) {
            entryRule.put("intent_codes", List.of("general_agent_request"));
            String keyword = firstNonBlank(workflowName, workflowCode, "workflow");
            entryRule.put("keywords", List.of(keyword));
            entryRule.put("regex_patterns", List.of());
            entryRule.put("examples", List.of());
            entryRule.put("priority", 100);
        }
        return writeJsonObject(entryRule);
    }

    private String normalizeProvidedWorkflowSnapshot(
            String snapshotJson,
            String workflowCode,
            String workflowName,
            String workflowDescription,
            String workflowVersion,
            String fallbackEntryRuleJson,
            String fallbackEditorMetaJson,
            String fallbackConfigJson
    ) {
        Map<String, Object> providedSnapshot = parseJsonObjectStrict(snapshotJson);
        String schemaVersion = stringValue(providedSnapshot.get("schema_version"));
        if (!WORKFLOW_SNAPSHOT_SCHEMA_V1.equals(schemaVersion)) {
            throw new RuntimeException("Unsupported workflow snapshot schema_version: " + schemaVersion);
        }

        Map<String, Object> providedDesigner = asMap(providedSnapshot.get("designer"));
        Map<String, Object> providedDefinition = asMap(providedDesigner.get("definition"));
        if (providedDefinition.isEmpty()) {
            throw new RuntimeException("Workflow snapshot designer.definition is required");
        }
        Map<String, Object> normalizedDefinition = normalizeWorkflowDefinition(providedDefinition);
        normalizedDefinition.put("workflow_code", workflowCode);
        normalizedDefinition.put("workflow_name", workflowName);
        normalizedDefinition.put("workflow_description", workflowDescription);
        normalizedDefinition.put("workflow_version", workflowVersion);

        Map<String, Object> normalizedDesigner = new LinkedHashMap<>(providedDesigner);
        normalizedDesigner.put("definition", normalizedDefinition);
        if (asMap(normalizedDesigner.get("entry_rule")).isEmpty()) {
            normalizedDesigner.put("entry_rule", parseJsonObject(fallbackEntryRuleJson));
        }
        Map<String, Object> workflowConfig = normalizedDesigner.containsKey("workflow_config")
                ? normalizeWorkflowConfig(asMap(normalizedDesigner.get("workflow_config")))
                : parseJsonObject(fallbackConfigJson);
        normalizedDesigner.put("workflow_config", workflowConfig);
        if (!normalizedDesigner.containsKey("editor_meta")) {
            normalizedDesigner.put("editor_meta", parseJsonObject(fallbackEditorMetaJson));
        }

        Map<String, Object> normalizedSnapshot = new LinkedHashMap<>(providedSnapshot);
        normalizedSnapshot.put("schema_version", WORKFLOW_SNAPSHOT_SCHEMA_V1);
        normalizedSnapshot.put("workflow", workflowMetadataMap(workflowCode, workflowName, workflowDescription, workflowVersion));
        normalizedSnapshot.put("designer", normalizedDesigner);
        return writeJsonObject(normalizedSnapshot);
    }

    private String buildCompatibilityWorkflowSnapshot(
            String workflowCode,
            String workflowName,
            String workflowDescription,
            String workflowVersion,
            String definitionJson,
            String entryRuleJson,
            String editorMetaJson,
            String configJson
    ) {
        Map<String, Object> normalizedDefinition = normalizeWorkflowDefinition(parseJsonObjectStrict(definitionJson));
        normalizedDefinition.put("workflow_code", workflowCode);
        normalizedDefinition.put("workflow_name", workflowName);
        normalizedDefinition.put("workflow_description", workflowDescription);
        normalizedDefinition.put("workflow_version", workflowVersion);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schema_version", WORKFLOW_SNAPSHOT_SCHEMA_V1);
        snapshot.put("workflow", workflowMetadataMap(workflowCode, workflowName, workflowDescription, workflowVersion));

        Map<String, Object> designer = new LinkedHashMap<>();
        designer.put("definition", normalizedDefinition);
        designer.put("entry_rule", parseJsonObject(entryRuleJson));
        Map<String, Object> workflowConfig = normalizeWorkflowConfig(parseJsonObject(configJson));
        designer.put("workflow_config", workflowConfig);
        designer.put("editor_meta", parseJsonObject(editorMetaJson));
        snapshot.put("designer", designer);
        return writeJsonObject(snapshot);
    }

    private Map<String, Object> workflowMetadataMap(String workflowCode, String workflowName, String workflowDescription, String workflowVersion) {
        Map<String, Object> workflowMeta = new LinkedHashMap<>();
        workflowMeta.put("workflow_code", workflowCode);
        workflowMeta.put("workflow_name", workflowName);
        workflowMeta.put("workflow_description", workflowDescription);
        workflowMeta.put("workflow_version", workflowVersion);
        return workflowMeta;
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
        normalized.put("workflow_description", source.get("workflow_description"));
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
        graph.put("graph_name", stringValue(rawGraph.get("graph_name")));
        graph.put("graph_description", stringValue(rawGraph.get("graph_description")));
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
            String description = stringValue(rawNode.get("description"));
            if (description != null) {
                node.put("description", description);
                config.putIfAbsent("description", description);
            }
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
            String source = firstNonBlank(
                    stringValue(edge.get("source")),
                    stringValue(edge.get("source_node_id")),
                    stringValue(edge.get("from"))
            );
            String target = firstNonBlank(
                    stringValue(edge.get("target")),
                    stringValue(edge.get("target_node_id")),
                    stringValue(edge.get("to"))
            );
            if (source == null || target == null) {
                continue;
            }
            Map<String, Object> normalizedEdge = new LinkedHashMap<>(edge);
            normalizedEdge.put("id", firstNonBlank(
                    stringValue(edge.get("id")),
                    stringValue(edge.get("edge_id")),
                    source + "_to_" + target + "_" + (++index)
            ));
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
        if (stringValue(graph.get("graph_name")) == null) {
            issues.add(issue(null, "graphs." + graphId + ".graph_name", "图 " + graphId + " 缺少流程名称"));
        }
        if (stringValue(graph.get("graph_description")) == null) {
            issues.add(issue(null, "graphs." + graphId + ".graph_description", "图 " + graphId + " 缺少流程描述"));
        }
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
                issues.add(issue(nodeId, "config.prompt", "决策节点缺少 prompt"));
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
        return Set.of("coordinator", "sub_agent").contains(nodeType);
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

    private String summarizeValidationIssues(List<Map<String, Object>> issues) {
        return issues.stream()
                .limit(3)
                .map(issue -> {
                    String field = stringValue(issue.get("field"));
                    String message = stringValue(issue.get("message"));
                    if (field == null) {
                        return message == null ? "未知校验问题" : message;
                    }
                    if (message == null) {
                        return field;
                    }
                    return field + " - " + message;
                })
                .collect(Collectors.joining("；"));
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
                if (stringValue(nodeConfig.get("group_id")) == null) {
                    issues.add(issue(nodeId, "config.group_id", "API 调用缺少 group_id"));
                }
                if (stringValue(nodeConfig.get("api_id")) == null) {
                    issues.add(issue(nodeId, "config.api_id", "API 调用缺少 api_id"));
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
            default -> issues.add(issue(nodeId, "config.invoke_type", "工具节点调用形式不受支持"));
        }
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

    private record ModelIntent(
            boolean matched,
            String intentCode,
            String workflowCode,
            String targetType,
            double confidence,
            String reason,
            boolean needClarification,
            String clarificationQuestion
    ) {
    }

    private record RuntimeRoutingModel(
            String routingModelCode,
            ModelConfigService.RuntimeModelBundle runtimeBundle
    ) {
    }

    private record WorkflowScore(WorkflowVersion version, int entryRuleScore, int modelScore, int priority) {
        int totalScore() {
            return entryRuleScore + modelScore;
        }
    }

    private record RegexCandidate(
            RoutingDecision.IntentCandidate candidate,
            int evidenceCount,
            int priority
    ) {
    }

    private record RagCandidate(
            RoutingDecision.IntentCandidate candidate,
            int priority
    ) {
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
