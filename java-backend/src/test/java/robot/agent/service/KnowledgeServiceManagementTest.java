package robot.agent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import robot.agent.config.KnowledgeProperties;
import robot.agent.dto.request.CreateKnowledgeBaseRequest;
import robot.agent.dto.request.CreateKnowledgeDocumentRequest;
import robot.agent.dto.response.KnowledgeBaseResponse;
import robot.agent.dto.response.KnowledgeDocumentResponse;
import robot.agent.model.KnowledgeBase;
import robot.agent.model.KnowledgeBaseStatus;
import robot.agent.model.KnowledgeDocument;
import robot.agent.model.KnowledgeDocumentStatus;
import robot.agent.model.KnowledgeTask;
import robot.agent.repository.KnowledgeBaseRepository;
import robot.agent.repository.KnowledgeDocumentRepository;
import robot.agent.repository.KnowledgeTaskRepository;
import robot.agent.repository.KnowledgeVersionRepository;
import robot.agent.service.knowledge.KnowledgeObjectStorage;
import robot.agent.service.knowledge.LegacyDocTextExtractor;
import robot.agent.service.knowledge.PythonKnowledgeClient;
import robot.agent.service.knowledge.SafeObjectKeyFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeServiceManagementTest {

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private KnowledgeVersionRepository knowledgeVersionRepository;

    @Mock
    private KnowledgeDocumentRepository knowledgeDocumentRepository;

    @Mock
    private KnowledgeTaskRepository knowledgeTaskRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private AuditService auditService;

    @Mock
    private KnowledgeObjectStorage knowledgeObjectStorage;

    @Mock
    private PythonKnowledgeClient pythonKnowledgeClient;

    @Mock
    private ModelConfigService modelConfigService;

    @Mock
    private LegacyDocTextExtractor legacyDocTextExtractor;

    private KnowledgeService knowledgeService;

    @BeforeEach
    void setUp() {
        knowledgeService = new KnowledgeService(
                knowledgeBaseRepository,
                knowledgeVersionRepository,
                knowledgeDocumentRepository,
                knowledgeTaskRepository,
                accessControlService,
                auditService,
                knowledgeObjectStorage,
                new SafeObjectKeyFactory(),
                new KnowledgeProperties(),
                pythonKnowledgeClient,
                modelConfigService,
                legacyDocTextExtractor
        );
    }

    @Test
    void getKnowledgeBasesHidesSoftDeletedSpaces() {
        KnowledgeBase active = new KnowledgeBase();
        active.setWorkspaceId(1L);
        active.setKbCode("kb_product");
        active.setName("产品知识");
        active.setStatus(KnowledgeBaseStatus.ACTIVE);

        KnowledgeBase deleted = new KnowledgeBase();
        deleted.setWorkspaceId(1L);
        deleted.setKbCode("kb_deleted");
        deleted.setName("已删除空间");
        deleted.setStatus(KnowledgeBaseStatus.DELETED);

        when(knowledgeBaseRepository.findByWorkspaceIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(deleted, active));

        List<KnowledgeBaseResponse> responses = knowledgeService.getKnowledgeBases(1L);

        assertThat(responses).extracting(KnowledgeBaseResponse::getKbCode).containsExactly("kb_product");
    }

    @Test
    void createKnowledgeBasePersistsBusinessFieldsOnly() {
        when(knowledgeBaseRepository.save(any(KnowledgeBase.class))).thenAnswer(invocation -> invocation.getArgument(0));
        CreateKnowledgeBaseRequest request = new CreateKnowledgeBaseRequest();
        request.setKbCode("kb_product");
        request.setName("产品知识");
        request.setDescription("产品说明与售后政策");

        KnowledgeBaseResponse response = knowledgeService.createKnowledgeBase("demo-admin", request);

        assertThat(response.getKbCode()).isEqualTo("kb_product");
        assertThat(response.getName()).isEqualTo(request.getName());
    }

    @Test
    void createKnowledgeBaseGeneratesInternalCodeWhenRequestOmitsIt() {
        when(knowledgeBaseRepository.save(any(KnowledgeBase.class))).thenAnswer(invocation -> invocation.getArgument(0));
        CreateKnowledgeBaseRequest request = new CreateKnowledgeBaseRequest();
        request.setName("产品知识");
        request.setDescription("产品说明与售后政策");

        KnowledgeBaseResponse response = knowledgeService.createKnowledgeBase("demo-admin", request);

        assertThat(response.getKbCode()).startsWith("kb_");
        assertThat(response.getKbCode()).hasSize(35);
    }

    @Test
    void updateKnowledgeBaseChangesBusinessFieldsOnly() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setWorkspaceId(1L);
        knowledgeBase.setKbCode("kb_product");
        knowledgeBase.setName("旧名称");
        knowledgeBase.setDescription("旧描述");
        knowledgeBase.setStatus(KnowledgeBaseStatus.ACTIVE);
        when(knowledgeBaseRepository.findByKbCode("kb_product")).thenReturn(Optional.of(knowledgeBase));
        when(knowledgeBaseRepository.save(any(KnowledgeBase.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreateKnowledgeBaseRequest request = new CreateKnowledgeBaseRequest();
        request.setName("产品知识");
        request.setDescription("产品说明与售后政策");

        KnowledgeBaseResponse response = knowledgeService.updateKnowledgeBase("demo-admin", "kb_product", request);

        assertThat(response.getName()).isEqualTo("产品知识");
        assertThat(response.getDescription()).isEqualTo("产品说明与售后政策");
    }

    @Test
    void deleteKnowledgeBaseMarksSpaceDeletedAndDocumentsDeleted() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setWorkspaceId(1L);
        knowledgeBase.setKbCode("kb_product");
        knowledgeBase.setStatus(KnowledgeBaseStatus.ACTIVE);
        KnowledgeDocument document = textDocument("doc_1", "kb_product", "产品保修", "保修期为一年。");
        when(knowledgeBaseRepository.findByKbCode("kb_product")).thenReturn(Optional.of(knowledgeBase));
        when(knowledgeDocumentRepository.findByKbCodeOrderByCreatedAtDesc("kb_product")).thenReturn(List.of(document));

        knowledgeService.deleteKnowledgeBase("demo-admin", "kb_product");

        assertThat(knowledgeBase.getStatus()).isEqualTo(KnowledgeBaseStatus.DELETED);
        assertThat(document.getStatus()).isEqualTo(KnowledgeDocumentStatus.DELETED);
        verify(knowledgeBaseRepository).save(knowledgeBase);
        verify(knowledgeDocumentRepository).save(document);
    }

    @Test
    void updateTextKnowledgeDocumentUpdatesContentAndReindexes() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setWorkspaceId(1L);
        knowledgeBase.setKbCode("kb_product");
        knowledgeBase.setCurrentVersion("v1");
        KnowledgeDocument document = textDocument("doc_1", "kb_product", "旧标题", "旧正文");
        document.setIndexVersion(1);
        when(knowledgeBaseRepository.findByKbCode("kb_product")).thenReturn(Optional.of(knowledgeBase));
        when(knowledgeDocumentRepository.findByDocId("doc_1")).thenReturn(Optional.of(document));
        when(knowledgeDocumentRepository.save(any(KnowledgeDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(knowledgeTaskRepository.save(any(KnowledgeTask.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(modelConfigService.buildRuntimeBundleForModel("model-431c4581ab84"))
                .thenReturn(new ModelConfigService.RuntimeModelBundle(List.of(Map.of("provider_code", "model-431c4581ab84-provider")), List.of(Map.of("model_code", "model-431c4581ab84"))));
        when(pythonKnowledgeClient.ingest(anyMap())).thenReturn(Map.of(
                "status", "SUCCEEDED",
                "chunk_count", 1,
                "generated_title", "新标题",
                "generated_summary", "新正文",
                "generated_keywords", List.of("产品")
        ));
        CreateKnowledgeDocumentRequest request = new CreateKnowledgeDocumentRequest();
        request.setTitle("新标题");
        request.setDescription("新描述");
        request.setContent("新正文");

        KnowledgeDocumentResponse response = knowledgeService.updateKnowledgeDocument("demo-admin", "doc_1", request);

        assertThat(response.getTitle()).isEqualTo("新标题");
        assertThat(response.getDescription()).isEqualTo("新描述");
        assertThat(response.getStatus()).isEqualTo(KnowledgeDocumentStatus.READY);
        assertThat(document.getRawContent()).isEqualTo("新正文");
        assertThat(document.getIndexVersion()).isEqualTo(2);
        ArgumentCaptor<Map<String, Object>> requestCaptor = ArgumentCaptor.forClass(Map.class);
        verify(pythonKnowledgeClient).ingest(requestCaptor.capture());
        assertThat(requestCaptor.getValue()).containsEntry("index_version", 2);
        assertThat(requestCaptor.getValue()).containsEntry("raw_content", "新正文");
        assertThat(requestCaptor.getValue()).containsEntry("embedding_model_code", "model-431c4581ab84");
    }

    @Test
    void deleteKnowledgeDocumentMarksDocumentDeleted() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setWorkspaceId(1L);
        knowledgeBase.setKbCode("kb_product");
        KnowledgeDocument document = textDocument("doc_1", "kb_product", "产品保修", "保修期为一年。");
        when(knowledgeDocumentRepository.findByDocId("doc_1")).thenReturn(Optional.of(document));
        when(knowledgeBaseRepository.findByKbCode("kb_product")).thenReturn(Optional.of(knowledgeBase));

        knowledgeService.deleteKnowledgeDocument("demo-admin", "doc_1");

        assertThat(document.getStatus()).isEqualTo(KnowledgeDocumentStatus.DELETED);
        verify(knowledgeDocumentRepository, atLeastOnce()).save(document);
    }

    private KnowledgeDocument textDocument(String docId, String kbCode, String title, String content) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setDocId(docId);
        document.setKbCode(kbCode);
        document.setVersion("v1");
        document.setTitle(title);
        document.setDescription("描述");
        document.setFilename(title + ".txt");
        document.setSourceType("TEXT");
        document.setRawContent(content);
        document.setRawContentType("text/plain; charset=utf-8");
        document.setStatus(KnowledgeDocumentStatus.READY);
        document.setIndexVersion(1);
        return document;
    }
}
