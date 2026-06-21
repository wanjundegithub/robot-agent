package robot.agent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import robot.agent.config.KnowledgeProperties;
import robot.agent.dto.request.KnowledgeSearchRequest;
import robot.agent.dto.response.KnowledgeSearchResponse;
import robot.agent.model.KnowledgeBase;
import robot.agent.model.KnowledgeDocument;
import robot.agent.model.KnowledgeDocumentStatus;
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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeSearchServiceTest {

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
    void searchKnowledgePassesRuntimeBundleAndMapsPythonResponse() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setWorkspaceId(1L);
        knowledgeBase.setKbCode("kb_product");
        KnowledgeDocument document = document("doc_1", KnowledgeDocumentStatus.READY);
        when(knowledgeBaseRepository.findByKbCode("kb_product")).thenReturn(Optional.of(knowledgeBase));
        when(knowledgeDocumentRepository.findByKbCodeOrderByCreatedAtDesc("kb_product")).thenReturn(List.of(document));
        when(modelConfigService.buildRuntimeBundleForModel("model-431c4581ab84"))
                .thenReturn(new ModelConfigService.RuntimeModelBundle(
                        List.of(Map.of("provider_code", "model-431c4581ab84-provider")),
                        List.of(Map.of("model_code", "model-431c4581ab84"))
                ));
        when(pythonKnowledgeClient.search(anyMap())).thenReturn(Map.of(
                "query", "保修期多久",
                "documents", List.of(Map.of(
                        "chunk_id", "chunk_1",
                        "doc_id", "doc_1",
                        "kb_code", "kb_product",
                        "title", "产品手册",
                        "content", "保修期为一年",
                        "score", 0.92
                )),
                "answer", "",
                "citations", List.of(Map.of("chunkId", "chunk_1", "docId", "doc_1", "score", 0.92)),
                "bestScore", 0.92
        ));
        KnowledgeSearchRequest request = new KnowledgeSearchRequest();
        request.setQuery("保修期多久");
        request.setKbCodes(List.of("kb_product"));
        request.setTopK(3);

        KnowledgeSearchResponse response = knowledgeService.searchKnowledge("demo-admin", request);

        assertThat(response.getBestScore()).isEqualTo(0.92d);
        assertThat(response.getDocuments()).hasSize(1);
        assertThat(response.getDocuments().get(0).getChunkId()).isEqualTo("chunk_1");
        assertThat(response.getCitations().get(0).getDocId()).isEqualTo("doc_1");
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(pythonKnowledgeClient).search(captor.capture());
        assertThat(captor.getValue()).containsEntry("embedding_model_code", "model-431c4581ab84");
        assertThat(captor.getValue()).containsEntry("top_k", 3);
        assertThat((List<?>) captor.getValue().get("provider_configs")).hasSize(1);
        assertThat((List<?>) captor.getValue().get("model_records")).hasSize(1);
    }

    @Test
    void searchKnowledgeFiltersSoftDeletedDocumentHits() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setWorkspaceId(1L);
        knowledgeBase.setKbCode("kb_product");
        KnowledgeDocument activeDocument = document("doc_active", KnowledgeDocumentStatus.READY);
        KnowledgeDocument deletedDocument = document("doc_deleted", KnowledgeDocumentStatus.DELETED);
        when(knowledgeBaseRepository.findByKbCode("kb_product")).thenReturn(Optional.of(knowledgeBase));
        when(knowledgeDocumentRepository.findByKbCodeOrderByCreatedAtDesc("kb_product"))
                .thenReturn(List.of(deletedDocument, activeDocument));
        when(modelConfigService.buildRuntimeBundleForModel("model-431c4581ab84"))
                .thenReturn(new ModelConfigService.RuntimeModelBundle(List.of(), List.of()));
        when(pythonKnowledgeClient.search(anyMap())).thenReturn(Map.of(
                "query", "保修期多久",
                "documents", List.of(
                        Map.of(
                                "chunk_id", "chunk_deleted",
                                "doc_id", "doc_deleted",
                                "kb_code", "kb_product",
                                "title", "已删除知识",
                                "content", "旧保修规则",
                                "score", 0.99
                        ),
                        Map.of(
                                "chunk_id", "chunk_active",
                                "doc_id", "doc_active",
                                "kb_code", "kb_product",
                                "title", "产品手册",
                                "content", "保修期为一年",
                                "score", 0.92
                        )
                ),
                "answer", "",
                "citations", List.of(
                        Map.of("chunkId", "chunk_deleted", "docId", "doc_deleted", "score", 0.99),
                        Map.of("chunkId", "chunk_active", "docId", "doc_active", "score", 0.92)
                ),
                "bestScore", 0.99
        ));
        KnowledgeSearchRequest request = new KnowledgeSearchRequest();
        request.setQuery("保修期多久");
        request.setKbCodes(List.of("kb_product"));

        KnowledgeSearchResponse response = knowledgeService.searchKnowledge("demo-admin", request);

        assertThat(response.getDocuments()).extracting(KnowledgeSearchResponse.DocumentHit::getDocId)
                .containsExactly("doc_active");
        assertThat(response.getCitations()).extracting(KnowledgeSearchResponse.Citation::getDocId)
                .containsExactly("doc_active");
        assertThat(response.getBestScore()).isEqualTo(0.92d);
    }

    @Test
    void searchKnowledgeUsesConfiguredEmbeddingModelCode() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setWorkspaceId(1L);
        knowledgeBase.setKbCode("kb_product");
        KnowledgeDocument document = document("doc_1", KnowledgeDocumentStatus.READY);
        when(knowledgeBaseRepository.findByKbCode("kb_product")).thenReturn(Optional.of(knowledgeBase));
        when(knowledgeDocumentRepository.findByKbCodeOrderByCreatedAtDesc("kb_product")).thenReturn(List.of(document));
        when(modelConfigService.buildRuntimeBundleForModel("model-431c4581ab84"))
                .thenReturn(new ModelConfigService.RuntimeModelBundle(
                        List.of(Map.of("provider_code", "model-431c4581ab84-provider")),
                        List.of(Map.of("model_code", "model-431c4581ab84"))
                ));
        when(pythonKnowledgeClient.search(anyMap())).thenReturn(Map.of(
                "query", "保修期多久",
                "documents", List.of(Map.of(
                        "chunk_id", "chunk_1",
                        "doc_id", "doc_1",
                        "kb_code", "kb_product",
                        "title", "产品手册",
                        "content", "保修期为一年",
                        "score", 0.92
                )),
                "answer", "",
                "citations", List.of(),
                "bestScore", 0.92
        ));
        KnowledgeSearchRequest request = new KnowledgeSearchRequest();
        request.setQuery("保修期多久");
        request.setKbCodes(List.of("kb_product"));

        knowledgeService.searchKnowledge("demo-admin", request);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(pythonKnowledgeClient).search(captor.capture());
        assertThat(captor.getValue()).containsEntry("embedding_model_code", "model-431c4581ab84");
        verify(modelConfigService).buildRuntimeBundleForModel("model-431c4581ab84");
    }

    private KnowledgeDocument document(String docId, KnowledgeDocumentStatus status) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setDocId(docId);
        document.setKbCode("kb_product");
        document.setStatus(status);
        return document;
    }
}
