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
        knowledgeBase.setEmbeddingModel("embedding-qwen3-8b");
        when(knowledgeBaseRepository.findByKbCode("kb_product")).thenReturn(Optional.of(knowledgeBase));
        when(modelConfigService.buildRuntimeBundleForModel("embedding-qwen3-8b"))
                .thenReturn(new ModelConfigService.RuntimeModelBundle(
                        List.of(Map.of("provider_code", "modelscope-embedding")),
                        List.of(Map.of("model_code", "embedding-qwen3-8b"))
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
        assertThat(captor.getValue()).containsEntry("embedding_model_code", "embedding-qwen3-8b");
        assertThat(captor.getValue()).containsEntry("top_k", 3);
        assertThat((List<?>) captor.getValue().get("provider_configs")).hasSize(1);
        assertThat((List<?>) captor.getValue().get("model_records")).hasSize(1);
    }
}
