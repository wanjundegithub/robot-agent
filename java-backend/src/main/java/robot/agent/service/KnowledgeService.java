package robot.agent.service;

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
import robot.agent.dto.response.KnowledgeVersionResponse;
import robot.agent.model.KnowledgeBase;
import robot.agent.model.KnowledgeBaseStatus;
import robot.agent.model.KnowledgeDocument;
import robot.agent.model.KnowledgeDocumentStatus;
import robot.agent.model.KnowledgeVersion;
import robot.agent.model.KnowledgeVersionStatus;
import robot.agent.repository.KnowledgeBaseRepository;
import robot.agent.repository.KnowledgeDocumentRepository;
import robot.agent.repository.KnowledgeVersionRepository;
import robot.agent.service.knowledge.KnowledgeObjectStorage;
import robot.agent.service.knowledge.SafeObjectKeyFactory;
import robot.agent.service.knowledge.StoredKnowledgeObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class KnowledgeService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeVersionRepository knowledgeVersionRepository;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final AccessControlService accessControlService;
    private final AuditService auditService;
    private final KnowledgeObjectStorage knowledgeObjectStorage;
    private final SafeObjectKeyFactory safeObjectKeyFactory;
    private final KnowledgeProperties knowledgeProperties;

    public KnowledgeService(
            KnowledgeBaseRepository knowledgeBaseRepository,
            KnowledgeVersionRepository knowledgeVersionRepository,
            KnowledgeDocumentRepository knowledgeDocumentRepository,
            AccessControlService accessControlService,
            AuditService auditService,
            KnowledgeObjectStorage knowledgeObjectStorage,
            SafeObjectKeyFactory safeObjectKeyFactory,
            KnowledgeProperties knowledgeProperties
    ) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.knowledgeVersionRepository = knowledgeVersionRepository;
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
        this.accessControlService = accessControlService;
        this.auditService = auditService;
        this.knowledgeObjectStorage = knowledgeObjectStorage;
        this.safeObjectKeyFactory = safeObjectKeyFactory;
        this.knowledgeProperties = knowledgeProperties;
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
            auditService.logAction(knowledgeBase.getWorkspaceId(), userId, "knowledge.document.upload", "knowledge_document", saved.getDocId(), filename, ApplicationConstants.HTTP_STATUS_OK);
            return KnowledgeDocumentResponse.fromEntity(saved);
        } catch (IOException exc) {
            throw new IllegalStateException("Failed to read uploaded knowledge file", exc);
        }
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
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
