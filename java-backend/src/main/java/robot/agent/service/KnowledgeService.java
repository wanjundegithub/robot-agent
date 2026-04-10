package robot.agent.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import robot.agent.dto.request.CreateKnowledgeBaseRequest;
import robot.agent.dto.request.CreateKnowledgeVersionRequest;
import robot.agent.dto.response.KnowledgeBaseResponse;
import robot.agent.dto.response.KnowledgeVersionResponse;
import robot.agent.model.KnowledgeBase;
import robot.agent.model.KnowledgeBaseStatus;
import robot.agent.model.KnowledgeVersion;
import robot.agent.model.KnowledgeVersionStatus;
import robot.agent.repository.KnowledgeBaseRepository;
import robot.agent.repository.KnowledgeVersionRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class KnowledgeService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeVersionRepository knowledgeVersionRepository;
    private final AccessControlService accessControlService;
    private final AuditService auditService;

    public KnowledgeService(
            KnowledgeBaseRepository knowledgeBaseRepository,
            KnowledgeVersionRepository knowledgeVersionRepository,
            AccessControlService accessControlService,
            AuditService auditService
    ) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.knowledgeVersionRepository = knowledgeVersionRepository;
        this.accessControlService = accessControlService;
        this.auditService = auditService;
    }

    public List<KnowledgeBaseResponse> getKnowledgeBases(Long workspaceId) {
        Long effectiveWorkspaceId = workspaceId == null ? 1L : workspaceId;
        return knowledgeBaseRepository.findByWorkspaceIdOrderByCreatedAtDesc(effectiveWorkspaceId)
                .stream()
                .map(KnowledgeBaseResponse::fromEntity)
                .collect(Collectors.toList());
    }

    public KnowledgeBaseResponse createKnowledgeBase(String userId, CreateKnowledgeBaseRequest request) {
        Long workspaceId = request.getWorkspaceId() == null ? 1L : request.getWorkspaceId();
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
        auditService.logAction(workspaceId, userId, "knowledge.create", "knowledge_base", saved.getKbCode(), request, 200);
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
        auditService.logAction(knowledgeBase.getWorkspaceId(), userId, "knowledge.version.create", "knowledge_version", kbCode + ":" + saved.getVersion(), request, 200);
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
        auditService.logAction(knowledgeBase.getWorkspaceId(), userId, "knowledge.version.publish", "knowledge_version", kbCode + ":" + version, null, 200);
        return KnowledgeVersionResponse.fromEntity(savedVersion);
    }
}
