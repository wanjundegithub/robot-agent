package robot.agent.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import robot.agent.common.ApplicationConstants;
import robot.agent.config.KnowledgeProperties;
import robot.agent.dto.request.CreateKnowledgeBaseRequest;
import robot.agent.dto.request.CreateKnowledgeDocumentRequest;
import robot.agent.dto.request.CreateKnowledgeVersionRequest;
import robot.agent.dto.response.KnowledgeBaseResponse;
import robot.agent.dto.response.KnowledgeDocumentResponse;
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
            LegacyDocTextExtractor legacyDocTextExtractor
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
    }

    public List<KnowledgeBaseResponse> getKnowledgeBases(Long workspaceId) {
        Long effectiveWorkspaceId = workspaceId == null ? ApplicationConstants.DEFAULT_WORKSPACE_ID : workspaceId;
        return knowledgeBaseRepository.findByWorkspaceIdOrderByCreatedAtDesc(effectiveWorkspaceId)
                .stream()
                .map(KnowledgeBaseResponse::fromEntity)
                .collect(Collectors.toList());
    }

    public KnowledgeBaseResponse createKnowledgeBase(String userId, CreateKnowledgeBaseRequest request) {
        Long workspaceId = request.getWorkspaceId() == null ? ApplicationConstants.DEFAULT_WORKSPACE_ID : request.getWorkspaceId();
        accessControlService.requireAnyRole(userId, workspaceId, Set.of("workflow_admin", "knowledge_admin"));

        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setWorkspaceId(workspaceId);
        knowledgeBase.setKbCode(request.getKbCode());
        knowledgeBase.setName(request.getName());
        knowledgeBase.setDescription(request.getDescription());
        knowledgeBase.setEmbeddingModel(request.getEmbeddingModel());
        knowledgeBase.setStatus(KnowledgeBaseStatus.ACTIVE);
        knowledgeBase.setCreatedBy(userId);
        knowledgeBase.setCreatedAt(LocalDateTime.now());

        KnowledgeBase saved = knowledgeBaseRepository.save(knowledgeBase);
        auditService.logAction(workspaceId, userId, "knowledge.create", "knowledge_base", saved.getKbCode(), request, ApplicationConstants.HTTP_STATUS_OK);
        return KnowledgeBaseResponse.fromEntity(saved);
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
        document.setFilename(firstNonBlank(request.getTitle(), "文本知识") + ".txt");
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
        runIngestion(knowledgeBase, saved, task, null);
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
            runIngestion(knowledgeBase, saved, task, extractLegacyDocTextIfNeeded(extension, file));
            auditService.logAction(knowledgeBase.getWorkspaceId(), userId, "knowledge.document.upload", "knowledge_document", saved.getDocId(), filename, ApplicationConstants.HTTP_STATUS_OK);
            return KnowledgeDocumentResponse.fromEntity(saved);
        } catch (IOException exc) {
            throw new IllegalStateException("Failed to read uploaded knowledge file", exc);
        }
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
        runIngestion(knowledgeBase, document, task, null);
        return KnowledgeTaskResponse.fromEntity(task);
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

    private void runIngestion(KnowledgeBase knowledgeBase, KnowledgeDocument document, KnowledgeTask task, String legacyDocText) {
        task.setStatus(KnowledgeTaskStatus.RUNNING);
        task.setStartedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        knowledgeTaskRepository.save(task);
        try {
            Map<String, Object> response = pythonKnowledgeClient.ingest(buildIngestRequest(knowledgeBase, document, task, legacyDocText));
            String status = String.valueOf(response == null ? "" : response.getOrDefault("status", ""));
            if (!"SUCCEEDED".equalsIgnoreCase(status)) {
                throw new IllegalStateException(String.valueOf(response == null ? "Python ingestion failed" : response.getOrDefault("error_message", "Python ingestion failed")));
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
            document.setProcessedAt(LocalDateTime.now());
        } catch (Exception exc) {
            task.setStatus(KnowledgeTaskStatus.FAILED);
            task.setErrorMessage(exc.getMessage());
            task.setCompletedAt(LocalDateTime.now());
            task.setUpdatedAt(LocalDateTime.now());
            document.setStatus(KnowledgeDocumentStatus.FAILED);
            document.setErrorMessage(exc.getMessage());
        }
        knowledgeTaskRepository.save(task);
        knowledgeDocumentRepository.save(document);
    }

    private Map<String, Object> buildIngestRequest(KnowledgeBase knowledgeBase, KnowledgeDocument document, KnowledgeTask task, String legacyDocText) {
        String embeddingModelCode = firstNonBlank(knowledgeBase.getEmbeddingModel(), knowledgeProperties.getEmbedding().getDefaultModelCode());
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
