package robot.agent.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import robot.agent.common.ApplicationConstants;
import robot.agent.config.KnowledgeProperties;
import robot.agent.dto.request.CreateKnowledgeBaseRequest;
import robot.agent.dto.request.CreateKnowledgeDocumentRequest;
import robot.agent.dto.request.CreateKnowledgeVersionRequest;
import robot.agent.dto.request.KnowledgeSearchRequest;
import robot.agent.dto.response.KnowledgeBaseResponse;
import robot.agent.dto.response.KnowledgeDocumentResponse;
import robot.agent.dto.response.KnowledgeSearchResponse;
import robot.agent.dto.response.KnowledgeTaskResponse;
import robot.agent.dto.response.KnowledgeVersionResponse;
import robot.agent.model.KnowledgeBase;
import robot.agent.model.KnowledgeBaseStatus;
import robot.agent.model.KnowledgeDocument;
import robot.agent.model.KnowledgeDocumentStatus;
import robot.agent.model.KnowledgeTask;
import robot.agent.model.KnowledgeTaskStage;
import robot.agent.model.KnowledgeTaskStatus;
import robot.agent.model.KnowledgeVersion;
import robot.agent.model.KnowledgeVersionStatus;
import robot.agent.repository.KnowledgeBaseRepository;
import robot.agent.repository.KnowledgeDocumentRepository;
import robot.agent.repository.KnowledgeTaskRepository;
import robot.agent.repository.KnowledgeVersionRepository;
import robot.agent.service.knowledge.KnowledgeObjectStorage;
import robot.agent.service.knowledge.LegacyDocTextExtractor;
import robot.agent.service.knowledge.PythonKnowledgeClient;
import robot.agent.service.knowledge.SafeObjectKeyFactory;
import robot.agent.service.knowledge.StoredKnowledgeObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class KnowledgeService {
    private static final Logger logger = LoggerFactory.getLogger(KnowledgeService.class);

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeVersionRepository knowledgeVersionRepository;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final KnowledgeTaskRepository knowledgeTaskRepository;
    private final AccessControlService accessControlService;
    private final AuditService auditService;
    private final KnowledgeObjectStorage knowledgeObjectStorage;
    private final SafeObjectKeyFactory safeObjectKeyFactory;
    private final KnowledgeProperties knowledgeProperties;
    private final PythonKnowledgeClient pythonKnowledgeClient;
    private final ModelConfigService modelConfigService;
    private final LegacyDocTextExtractor legacyDocTextExtractor;
    private final TaskExecutor taskExecutor;

    @Autowired
    public KnowledgeService(
            KnowledgeBaseRepository knowledgeBaseRepository,
            KnowledgeVersionRepository knowledgeVersionRepository,
            KnowledgeDocumentRepository knowledgeDocumentRepository,
            KnowledgeTaskRepository knowledgeTaskRepository,
            AccessControlService accessControlService,
            AuditService auditService,
            KnowledgeObjectStorage knowledgeObjectStorage,
            SafeObjectKeyFactory safeObjectKeyFactory,
            KnowledgeProperties knowledgeProperties,
            PythonKnowledgeClient pythonKnowledgeClient,
            ModelConfigService modelConfigService,
            LegacyDocTextExtractor legacyDocTextExtractor,
            TaskExecutor taskExecutor
    ) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.knowledgeVersionRepository = knowledgeVersionRepository;
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
        this.knowledgeTaskRepository = knowledgeTaskRepository;
        this.accessControlService = accessControlService;
        this.auditService = auditService;
        this.knowledgeObjectStorage = knowledgeObjectStorage;
        this.safeObjectKeyFactory = safeObjectKeyFactory;
        this.knowledgeProperties = knowledgeProperties;
        this.pythonKnowledgeClient = pythonKnowledgeClient;
        this.modelConfigService = modelConfigService;
        this.legacyDocTextExtractor = legacyDocTextExtractor;
        this.taskExecutor = taskExecutor;
    }

    public List<KnowledgeBaseResponse> getKnowledgeBases(Long workspaceId) {
        Long effectiveWorkspaceId = workspaceId == null ? ApplicationConstants.DEFAULT_WORKSPACE_ID : workspaceId;
        return knowledgeBaseRepository.findByWorkspaceIdOrderByCreatedAtDesc(effectiveWorkspaceId)
                .stream()
                .filter(knowledgeBase -> knowledgeBase.getStatus() != KnowledgeBaseStatus.DELETED)
                .map(KnowledgeBaseResponse::fromEntity)
                .collect(Collectors.toList());
    }

    public KnowledgeBaseResponse createKnowledgeBase(String userId, CreateKnowledgeBaseRequest request) {
        Long workspaceId = request.getWorkspaceId() == null ? ApplicationConstants.DEFAULT_WORKSPACE_ID : request.getWorkspaceId();
        accessControlService.requireAnyRole(userId, workspaceId, Set.of("workflow_admin", "knowledge_admin"));

        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setWorkspaceId(workspaceId);
        knowledgeBase.setKbCode(generateKnowledgeBaseCode(request.getKbCode()));
        knowledgeBase.setName(request.getName());
        knowledgeBase.setDescription(blankToNull(request.getDescription()));
        knowledgeBase.setStatus(KnowledgeBaseStatus.ACTIVE);
        knowledgeBase.setCreatedBy(userId);
        knowledgeBase.setCreatedAt(LocalDateTime.now());

        KnowledgeBase saved = knowledgeBaseRepository.save(knowledgeBase);
        auditService.logAction(workspaceId, userId, "knowledge.create", "knowledge_base", saved.getKbCode(), request, ApplicationConstants.HTTP_STATUS_OK);
        return KnowledgeBaseResponse.fromEntity(saved);
    }

    public KnowledgeBaseResponse updateKnowledgeBase(String userId, String kbCode, CreateKnowledgeBaseRequest request) {
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findByKbCode(kbCode)
                .orElseThrow(() -> new RuntimeException("Knowledge base not found: " + kbCode));
        accessControlService.requireAnyRole(userId, knowledgeBase.getWorkspaceId(), Set.of("workflow_admin", "knowledge_admin"));
        if (knowledgeBase.getStatus() == KnowledgeBaseStatus.DELETED) {
            throw new IllegalStateException("Knowledge base has been deleted: " + kbCode);
        }
        String name = request.getName() == null ? "" : request.getName().trim();
        if (name.isBlank()) {
            throw new IllegalArgumentException("Knowledge base name must not be empty");
        }
        knowledgeBase.setName(name);
        knowledgeBase.setDescription(blankToNull(request.getDescription()));
        KnowledgeBase saved = knowledgeBaseRepository.save(knowledgeBase);
        auditService.logAction(knowledgeBase.getWorkspaceId(), userId, "knowledge.update", "knowledge_base", saved.getKbCode(), request, ApplicationConstants.HTTP_STATUS_OK);
        return KnowledgeBaseResponse.fromEntity(saved);
    }

    public void deleteKnowledgeBase(String userId, String kbCode) {
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findByKbCode(kbCode)
                .orElseThrow(() -> new RuntimeException("Knowledge base not found: " + kbCode));
        accessControlService.requireAnyRole(userId, knowledgeBase.getWorkspaceId(), Set.of("workflow_admin", "knowledge_admin"));
        knowledgeBase.setStatus(KnowledgeBaseStatus.DELETED);
        knowledgeBaseRepository.save(knowledgeBase);
        knowledgeDocumentRepository.findByKbCodeOrderByCreatedAtDesc(kbCode).forEach(document -> {
            if (document.getStatus() != KnowledgeDocumentStatus.DELETED) {
                document.setStatus(KnowledgeDocumentStatus.DELETED);
                knowledgeDocumentRepository.save(document);
            }
        });
        auditService.logAction(knowledgeBase.getWorkspaceId(), userId, "knowledge.delete", "knowledge_base", kbCode, null, ApplicationConstants.HTTP_STATUS_OK);
    }

    public List<KnowledgeVersionResponse> getKnowledgeVersions(String kbCode) {
        return knowledgeVersionRepository.findByKbCodeOrderByCreatedAtDesc(kbCode)
                .stream()
                .map(KnowledgeVersionResponse::fromEntity)
                .collect(Collectors.toList());
    }

    public KnowledgeVersionResponse createKnowledgeVersion(String userId, String kbCode, CreateKnowledgeVersionRequest request) {
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findByKbCode(kbCode)
                .orElseThrow(() -> new RuntimeException("Knowledge base not found: " + kbCode));
        accessControlService.requireAnyRole(userId, knowledgeBase.getWorkspaceId(), Set.of("workflow_admin", "knowledge_admin"));

        KnowledgeVersion version = new KnowledgeVersion();
        version.setKbCode(kbCode);
        version.setVersion(request.getVersion());
        version.setStatus(KnowledgeVersionStatus.DRAFT);
        version.setChunkCount(request.getChunkCount() == null ? 0 : request.getChunkCount());
        version.setDocCount(request.getDocCount() == null ? 0 : request.getDocCount());
        version.setCreatedBy(userId);
        version.setCreatedAt(LocalDateTime.now());

        KnowledgeVersion saved = knowledgeVersionRepository.save(version);
        auditService.logAction(knowledgeBase.getWorkspaceId(), userId, "knowledge.version.create", "knowledge_version", kbCode + ":" + saved.getVersion(), request, ApplicationConstants.HTTP_STATUS_OK);
        return KnowledgeVersionResponse.fromEntity(saved);
    }

    public KnowledgeVersionResponse publishKnowledgeVersion(String userId, String kbCode, String version) {
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findByKbCode(kbCode)
                .orElseThrow(() -> new RuntimeException("Knowledge base not found: " + kbCode));
        accessControlService.requireAnyRole(userId, knowledgeBase.getWorkspaceId(), Set.of("workflow_admin", "knowledge_admin"));

        KnowledgeVersion knowledgeVersion = knowledgeVersionRepository.findByKbCodeAndVersion(kbCode, version)
                .orElseThrow(() -> new RuntimeException("Knowledge version not found: " + kbCode + "@" + version));
        knowledgeVersion.setStatus(KnowledgeVersionStatus.PUBLISHED);
        knowledgeVersion.setPublishedAt(LocalDateTime.now());
        KnowledgeVersion savedVersion = knowledgeVersionRepository.save(knowledgeVersion);

        knowledgeBase.setCurrentVersion(version);
        knowledgeBaseRepository.save(knowledgeBase);
        auditService.logAction(knowledgeBase.getWorkspaceId(), userId, "knowledge.version.publish", "knowledge_version", kbCode + ":" + version, null, ApplicationConstants.HTTP_STATUS_OK);
        return KnowledgeVersionResponse.fromEntity(savedVersion);
    }

    public List<KnowledgeDocumentResponse> getKnowledgeDocuments(String kbCode) {
        return knowledgeDocumentRepository.findByKbCodeOrderByCreatedAtDesc(kbCode)
                .stream()
                .filter(document -> document.getStatus() != KnowledgeDocumentStatus.DELETED)
                .map(KnowledgeDocumentResponse::fromEntity)
                .collect(Collectors.toList());
    }

    public KnowledgeDocumentResponse createTextKnowledgeDocument(String userId, String kbCode, CreateKnowledgeDocumentRequest request) {
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findByKbCode(kbCode)
                .orElseThrow(() -> new RuntimeException("Knowledge base not found: " + kbCode));
        accessControlService.requireAnyRole(userId, knowledgeBase.getWorkspaceId(), Set.of("workflow_admin", "knowledge_admin"));

        String content = request.getContent() == null ? "" : request.getContent().trim();
        if (content.isBlank()) {
            throw new IllegalArgumentException("Knowledge text content must not be empty");
        }

        String docId = "doc_" + UUID.randomUUID().toString().replace("-", "");
        KnowledgeDocument document = new KnowledgeDocument();
        document.setKbCode(kbCode);
        document.setVersion(firstNonBlank(knowledgeBase.getCurrentVersion(), "1"));
        document.setDocId(docId);
        String title = firstNonBlank(request.getTitle(), "文本知识");
        document.setTitle(title);
        document.setDescription(blankToNull(request.getDescription()));
        document.setFilename(title + ".txt");
        document.setSourceType("TEXT");
        document.setRawContentType("text/plain; charset=utf-8");
        document.setStatus(KnowledgeDocumentStatus.PENDING);
        document.setUploadedAt(LocalDateTime.now());
        document.setIndexVersion(1);

        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        document.setFileSize((long) bytes.length);
        document.setContentHash(sha256Hex(bytes));
        if (bytes.length <= 64 * 1024) {
            document.setRawContent(content);
        } else {
            String objectKey = safeObjectKeyFactory.longTextObjectKey(knowledgeBase.getWorkspaceId(), kbCode, docId);
            StoredKnowledgeObject stored = knowledgeObjectStorage.put(
                    objectKey,
                    new ByteArrayInputStream(bytes),
                    bytes.length,
                    "text/plain; charset=utf-8"
            );
            document.setRawBucket(stored.bucket());
            document.setRawObjectKey(stored.objectKey());
            document.setRawEtag(stored.etag());
        }

        KnowledgeDocument saved = knowledgeDocumentRepository.save(document);
        KnowledgeTask task = createQueuedTask(saved);
        scheduleIngestion(task.getTaskId(), null);
        auditService.logAction(knowledgeBase.getWorkspaceId(), userId, "knowledge.document.create_text", "knowledge_document", saved.getDocId(), request, ApplicationConstants.HTTP_STATUS_OK);
        return KnowledgeDocumentResponse.fromEntity(saved);
    }

    public KnowledgeDocumentResponse uploadKnowledgeDocument(String userId, String kbCode, MultipartFile file) {
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findByKbCode(kbCode)
                .orElseThrow(() -> new RuntimeException("Knowledge base not found: " + kbCode));
        accessControlService.requireAnyRole(userId, knowledgeBase.getWorkspaceId(), Set.of("workflow_admin", "knowledge_admin"));

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Knowledge file must not be empty");
        }
        String filename = firstNonBlank(file.getOriginalFilename(), "knowledge.txt");
        String extension = filename.contains(".")
                ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
                : "";
        if (!knowledgeProperties.getStorage().getAllowedTypes().contains(extension)) {
            throw new IllegalArgumentException("Unsupported knowledge file type: " + extension);
        }
        long maxBytes = knowledgeProperties.getStorage().getMaxFileSizeMb() * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw new IllegalArgumentException("Knowledge file exceeds max size");
        }

        String docId = "doc_" + UUID.randomUUID().toString().replace("-", "");
        String objectKey = safeObjectKeyFactory.rawObjectKey(knowledgeBase.getWorkspaceId(), kbCode, docId, filename);
        try (InputStream inputStream = file.getInputStream()) {
            StoredKnowledgeObject stored = knowledgeObjectStorage.put(objectKey, inputStream, file.getSize(), file.getContentType());
            KnowledgeDocument document = new KnowledgeDocument();
            document.setKbCode(kbCode);
            document.setVersion(firstNonBlank(knowledgeBase.getCurrentVersion(), "1"));
            document.setDocId(docId);
            document.setTitle(stripTextExtension(filename));
            document.setFilename(filename);
            document.setFileSize(file.getSize());
            document.setSourceType("FILE");
            document.setRawBucket(stored.bucket());
            document.setRawObjectKey(stored.objectKey());
            document.setRawEtag(stored.etag());
            document.setRawContentType(stored.contentType());
            document.setStatus(KnowledgeDocumentStatus.PENDING);
            document.setUploadedAt(LocalDateTime.now());
            document.setIndexVersion(1);
            KnowledgeDocument saved = knowledgeDocumentRepository.save(document);
            KnowledgeTask task = createQueuedTask(saved);
            scheduleIngestion(task.getTaskId(), extractLegacyDocTextIfNeeded(extension, file));
            auditService.logAction(knowledgeBase.getWorkspaceId(), userId, "knowledge.document.upload", "knowledge_document", saved.getDocId(), filename, ApplicationConstants.HTTP_STATUS_OK);
            return KnowledgeDocumentResponse.fromEntity(saved);
        } catch (IOException exc) {
            throw new IllegalStateException("Failed to read uploaded knowledge file", exc);
        }
    }

    public KnowledgeDocumentResponse updateKnowledgeDocument(String userId, String docId, CreateKnowledgeDocumentRequest request) {
        KnowledgeDocument document = knowledgeDocumentRepository.findByDocId(docId)
                .orElseThrow(() -> new RuntimeException("Knowledge document not found: " + docId));
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findByKbCode(document.getKbCode())
                .orElseThrow(() -> new RuntimeException("Knowledge base not found: " + document.getKbCode()));
        accessControlService.requireAnyRole(userId, knowledgeBase.getWorkspaceId(), Set.of("workflow_admin", "knowledge_admin"));
        if (document.getStatus() == KnowledgeDocumentStatus.DELETED) {
            throw new IllegalStateException("Knowledge document has been deleted: " + docId);
        }

        String title = firstNonBlank(request.getTitle(), stripTextExtension(document.getFilename()));
        document.setTitle(title);
        document.setDescription(blankToNull(request.getDescription()));

        String content = request.getContent() == null ? null : request.getContent().trim();
        if ("TEXT".equalsIgnoreCase(document.getSourceType()) && content != null) {
            if (content.isBlank()) {
                throw new IllegalArgumentException("Knowledge text content must not be empty");
            }
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            document.setFilename(title + ".txt");
            document.setRawContent(content);
            document.setFileSize((long) bytes.length);
            document.setContentHash(sha256Hex(bytes));
            document.setRawContentType("text/plain; charset=utf-8");
            document.setRawBucket(null);
            document.setRawObjectKey(null);
            document.setRawEtag(null);
            document.setStatus(KnowledgeDocumentStatus.PENDING);
            document.setErrorMessage(null);
            document.setIndexVersion((document.getIndexVersion() == null ? 0 : document.getIndexVersion()) + 1);
            KnowledgeDocument saved = knowledgeDocumentRepository.save(document);
            KnowledgeTask task = createQueuedTask(saved);
            scheduleIngestion(task.getTaskId(), null);
            auditService.logAction(knowledgeBase.getWorkspaceId(), userId, "knowledge.document.update_text", "knowledge_document", saved.getDocId(), request, ApplicationConstants.HTTP_STATUS_OK);
            return KnowledgeDocumentResponse.fromEntity(saved);
        }

        KnowledgeDocument saved = knowledgeDocumentRepository.save(document);
        auditService.logAction(knowledgeBase.getWorkspaceId(), userId, "knowledge.document.update", "knowledge_document", saved.getDocId(), request, ApplicationConstants.HTTP_STATUS_OK);
        return KnowledgeDocumentResponse.fromEntity(saved);
    }

    public void deleteKnowledgeDocument(String userId, String docId) {
        KnowledgeDocument document = knowledgeDocumentRepository.findByDocId(docId)
                .orElseThrow(() -> new RuntimeException("Knowledge document not found: " + docId));
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findByKbCode(document.getKbCode())
                .orElseThrow(() -> new RuntimeException("Knowledge base not found: " + document.getKbCode()));
        accessControlService.requireAnyRole(userId, knowledgeBase.getWorkspaceId(), Set.of("workflow_admin", "knowledge_admin"));
        document.setStatus(KnowledgeDocumentStatus.DELETED);
        document.setErrorMessage(null);
        knowledgeDocumentRepository.save(document);
        auditService.logAction(knowledgeBase.getWorkspaceId(), userId, "knowledge.document.delete", "knowledge_document", docId, null, ApplicationConstants.HTTP_STATUS_OK);
    }

    public KnowledgeTaskResponse getKnowledgeTask(String taskId) {
        return KnowledgeTaskResponse.fromEntity(knowledgeTaskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new RuntimeException("Knowledge task not found: " + taskId)));
    }

    public List<KnowledgeTaskResponse> getDocumentTasks(String docId) {
        return knowledgeTaskRepository.findByDocIdOrderByCreatedAtDesc(docId)
                .stream()
                .map(KnowledgeTaskResponse::fromEntity)
                .collect(Collectors.toList());
    }

    public KnowledgeTaskResponse retryKnowledgeTask(String userId, String taskId) {
        KnowledgeTask task = knowledgeTaskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new RuntimeException("Knowledge task not found: " + taskId));
        KnowledgeDocument document = knowledgeDocumentRepository.findByDocId(task.getDocId())
                .orElseThrow(() -> new RuntimeException("Knowledge document not found: " + task.getDocId()));
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findByKbCode(task.getKbCode())
                .orElseThrow(() -> new RuntimeException("Knowledge base not found: " + task.getKbCode()));
        accessControlService.requireAnyRole(userId, knowledgeBase.getWorkspaceId(), Set.of("workflow_admin", "knowledge_admin"));

        task.setRetryCount((task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1);
        task.setStatus(KnowledgeTaskStatus.QUEUED);
        task.setStage(KnowledgeTaskStage.RAW_SAVED);
        task.setProgress(0);
        task.setErrorMessage(null);
        task.setUpdatedAt(LocalDateTime.now());
        knowledgeTaskRepository.save(task);
        scheduleIngestion(task.getTaskId(), null);
        return KnowledgeTaskResponse.fromEntity(task);
    }

    public void deleteKnowledgeTask(String userId, String taskId) {
        KnowledgeTask task = knowledgeTaskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new RuntimeException("Knowledge task not found: " + taskId));
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findByKbCode(task.getKbCode())
                .orElseThrow(() -> new RuntimeException("Knowledge base not found: " + task.getKbCode()));
        accessControlService.requireAnyRole(userId, knowledgeBase.getWorkspaceId(), Set.of("workflow_admin", "knowledge_admin"));
        knowledgeTaskRepository.delete(task);
    }

    public KnowledgeSearchResponse searchKnowledge(String userId, KnowledgeSearchRequest request) {
        if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            throw new IllegalArgumentException("Knowledge search query must not be empty");
        }
        List<String> kbCodes = request.getKbCodes() == null ? List.of() : request.getKbCodes();
        if (kbCodes.isEmpty()) {
            throw new IllegalArgumentException("At least one knowledge base is required");
        }

        List<KnowledgeBase> knowledgeBases = kbCodes.stream()
                .map(kbCode -> knowledgeBaseRepository.findByKbCode(kbCode)
                        .orElseThrow(() -> new RuntimeException("Knowledge base not found: " + kbCode)))
                .toList();
        for (KnowledgeBase knowledgeBase : knowledgeBases) {
            accessControlService.requireAnyRole(userId, knowledgeBase.getWorkspaceId(), Set.of("workflow_admin", "knowledge_admin", "viewer"));
        }

        String embeddingModelCode = resolveEmbeddingModelCode();
        ModelConfigService.RuntimeModelBundle bundle = modelConfigService.buildRuntimeBundleForModel(embeddingModelCode);
        Map<String, Object> pythonRequest = new LinkedHashMap<>();
        pythonRequest.put("query", request.getQuery());
        pythonRequest.put("kb_codes", kbCodes);
        pythonRequest.put("retrieval_mode", firstNonBlank(request.getRetrievalMode(), knowledgeProperties.getRetrieval().getMode()));
        pythonRequest.put("top_k", request.getTopK() == null ? knowledgeProperties.getRetrieval().getTopK() : request.getTopK());
        pythonRequest.put("score_threshold", request.getScoreThreshold() == null ? knowledgeProperties.getRetrieval().getScoreThreshold() : request.getScoreThreshold());
        pythonRequest.put("embedding_model_code", embeddingModelCode);
        pythonRequest.put("provider_configs", bundle.providerConfigs());
        pythonRequest.put("model_records", bundle.modelRecords());
        pythonRequest.put("generate_answer", request.getGenerateAnswer() == null ? Boolean.TRUE : request.getGenerateAnswer());
        KnowledgeSearchResponse response = KnowledgeSearchResponse.fromMap(pythonKnowledgeClient.search(pythonRequest));
        int originalDocumentCount = response.getDocuments().size();
        Set<String> activeDocIds = kbCodes.stream()
                .flatMap(kbCode -> knowledgeDocumentRepository.findByKbCodeOrderByCreatedAtDesc(kbCode).stream())
                .filter(document -> document.getStatus() != KnowledgeDocumentStatus.DELETED)
                .map(KnowledgeDocument::getDocId)
                .collect(Collectors.toSet());
        response.setDocuments(response.getDocuments().stream()
                .filter(hit -> activeDocIds.contains(hit.getDocId()))
                .collect(Collectors.toList()));
        response.setCitations(response.getCitations().stream()
                .filter(citation -> activeDocIds.contains(citation.getDocId()))
                .collect(Collectors.toList()));
        response.setBestScore(response.getDocuments().stream()
                .map(KnowledgeSearchResponse.DocumentHit::getScore)
                .mapToDouble(value -> value == null ? 0.0d : value)
                .max()
                .orElse(0.0d));
        if (response.getDocuments().size() != originalDocumentCount) {
            response.setAnswer("");
        }
        return response;
    }

    private KnowledgeTask createQueuedTask(KnowledgeDocument document) {
        KnowledgeTask task = new KnowledgeTask();
        task.setTaskId("task_" + UUID.randomUUID().toString().replace("-", ""));
        task.setDocId(document.getDocId());
        task.setKbCode(document.getKbCode());
        task.setStage(KnowledgeTaskStage.RAW_SAVED);
        task.setStatus(KnowledgeTaskStatus.QUEUED);
        task.setProgress(0);
        return knowledgeTaskRepository.save(task);
    }

    private void scheduleIngestion(String taskId, String legacyDocText) {
        Runnable work = () -> runIngestionByTaskId(taskId, legacyDocText);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    taskExecutor.execute(work);
                }
            });
            return;
        }
        taskExecutor.execute(work);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void runIngestionByTaskId(String taskId, String legacyDocText) {
        KnowledgeTask task = knowledgeTaskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new RuntimeException("Knowledge task not found: " + taskId));
        KnowledgeDocument document = knowledgeDocumentRepository.findByDocId(task.getDocId())
                .orElseThrow(() -> new RuntimeException("Knowledge document not found: " + task.getDocId()));
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findByKbCode(task.getKbCode())
                .orElseThrow(() -> new RuntimeException("Knowledge base not found: " + task.getKbCode()));
        runIngestion(knowledgeBase, document, task, legacyDocText);
    }

    private void runIngestion(KnowledgeBase knowledgeBase, KnowledgeDocument document, KnowledgeTask task, String legacyDocText) {
        task.setStatus(KnowledgeTaskStatus.RUNNING);
        task.setStartedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        knowledgeTaskRepository.save(task);
        try {
            Map<String, Object> response = pythonKnowledgeClient.ingest(buildIngestRequest(knowledgeBase, document, task, legacyDocText));
            String status = String.valueOf(response == null ? "" : response.getOrDefault("status", ""));
            if (!"SUCCEEDED".equalsIgnoreCase(status)) {
                throw new IllegalStateException(formatPythonFailure(response));
            }

            task.setStatus(KnowledgeTaskStatus.SUCCEEDED);
            task.setStage(KnowledgeTaskStage.INDEXED);
            task.setProgress(100);
            task.setCompletedAt(LocalDateTime.now());
            task.setUpdatedAt(LocalDateTime.now());
            document.setStatus(KnowledgeDocumentStatus.READY);
            document.setChunkCount(intValue(response.get("chunk_count"), 0));
            document.setGeneratedTitle(stringValue(response.get("generated_title")));
            document.setGeneratedSummary(stringValue(response.get("generated_summary")));
            document.setGeneratedKeywords(joinKeywords(response.get("generated_keywords")));
            document.setErrorMessage(null);
            document.setProcessedAt(LocalDateTime.now());
        } catch (Exception exc) {
            String errorMessage = formatException(exc);
            logger.warn("knowledge.ingestion.failed taskId={} docId={} kbCode={} error={}", task.getTaskId(), document.getDocId(), document.getKbCode(), errorMessage, exc);
            task.setStatus(KnowledgeTaskStatus.FAILED);
            task.setErrorMessage(errorMessage);
            task.setCompletedAt(LocalDateTime.now());
            task.setUpdatedAt(LocalDateTime.now());
            document.setStatus(KnowledgeDocumentStatus.FAILED);
            document.setErrorMessage(errorMessage);
        }
        knowledgeTaskRepository.save(task);
        knowledgeDocumentRepository.save(document);
    }

    private String formatPythonFailure(Map<String, Object> response) {
        if (response == null) {
            return "Python ingestion failed: empty response";
        }
        Object errorMessage = response.get("error_message");
        if (errorMessage != null && !String.valueOf(errorMessage).isBlank()) {
            return String.valueOf(errorMessage);
        }
        return "Python ingestion failed: " + response;
    }

    private String formatException(Exception exc) {
        String message = exc.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        return exc.getClass().getSimpleName();
    }

    private Map<String, Object> buildIngestRequest(KnowledgeBase knowledgeBase, KnowledgeDocument document, KnowledgeTask task, String legacyDocText) {
        String embeddingModelCode = resolveEmbeddingModelCode();
        ModelConfigService.RuntimeModelBundle bundle = modelConfigService.buildRuntimeBundleForModel(embeddingModelCode);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("task_id", task.getTaskId());
        request.put("doc_id", document.getDocId());
        request.put("kb_code", document.getKbCode());
        request.put("index_version", document.getIndexVersion() == null ? 1 : document.getIndexVersion());
        request.put("title", firstNonBlank(document.getGeneratedTitle(), stripTextExtension(document.getFilename())));
        request.put("source_type", document.getSourceType());
        request.put("filename", document.getFilename());
        request.put("raw_content", document.getRawContent());
        request.put("raw_object_url", document.getRawObjectKey() == null ? null : knowledgeObjectStorage.presignedGetUrl(document.getRawObjectKey()).toString());
        request.put("legacy_doc_text", legacyDocText);
        request.put("embedding_model_code", embeddingModelCode);
        request.put("provider_configs", bundle.providerConfigs());
        request.put("model_records", bundle.modelRecords());
        return request;
    }

    private String extractLegacyDocTextIfNeeded(String extension, MultipartFile file) {
        if (!"doc".equals(extension)) {
            return null;
        }
        try (InputStream inputStream = file.getInputStream()) {
            return legacyDocTextExtractor.extract(inputStream);
        } catch (IOException exc) {
            throw new IllegalStateException("Failed to extract legacy doc text", exc);
        }
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String resolveEmbeddingModelCode() {
        return firstNonBlank(knowledgeProperties.getEmbedding().getDefaultModelCode(), "");
    }

    private String generateKnowledgeBaseCode(String requestedCode) {
        return firstNonBlank(requestedCode, "kb_" + UUID.randomUUID().toString().replace("-", ""));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String stripTextExtension(String filename) {
        String value = firstNonBlank(filename, "");
        return value.endsWith(".txt") ? value.substring(0, value.length() - 4) : value;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exc) {
            return fallback;
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String joinKeywords(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.joining(","));
        }
        return value == null ? null : String.valueOf(value);
    }

    private String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exc) {
            throw new IllegalStateException("SHA-256 is not available", exc);
        }
    }
}
