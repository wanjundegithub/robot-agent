package robot.agent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import robot.agent.config.KnowledgeProperties;
import robot.agent.dto.request.CreateKnowledgeDocumentRequest;
import robot.agent.dto.response.KnowledgeDocumentResponse;
import robot.agent.model.KnowledgeBase;
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
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeServiceDocumentTest {

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
    private List<Runnable> scheduledTasks;

    @BeforeEach
    void setUp() {
        scheduledTasks = new ArrayList<>();
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
                legacyDocTextExtractor,
                scheduledTasks::add
        );
    }

    @Test
    void createTextKnowledgeDocumentStoresShortContentInMetadata() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setWorkspaceId(1L);
        knowledgeBase.setKbCode("kb_product");
        knowledgeBase.setCurrentVersion("v1");
        AtomicReference<KnowledgeDocument> documentRef = new AtomicReference<>();
        AtomicReference<KnowledgeTask> taskRef = new AtomicReference<>();
        when(knowledgeBaseRepository.findByKbCode("kb_product")).thenReturn(Optional.of(knowledgeBase));
        when(knowledgeDocumentRepository.save(any(KnowledgeDocument.class))).thenAnswer(invocation -> {
            KnowledgeDocument document = invocation.getArgument(0);
            documentRef.set(document);
            return document;
        });
        when(knowledgeTaskRepository.save(any(KnowledgeTask.class))).thenAnswer(invocation -> {
            KnowledgeTask task = invocation.getArgument(0);
            taskRef.set(task);
            return task;
        });
        when(knowledgeDocumentRepository.findByDocId(any())).thenAnswer(invocation -> Optional.of(documentRef.get()));
        when(knowledgeTaskRepository.findByTaskId(any())).thenAnswer(invocation -> Optional.of(taskRef.get()));
        when(modelConfigService.buildRuntimeBundleForModel("model-431c4581ab84"))
                .thenReturn(new ModelConfigService.RuntimeModelBundle(List.of(Map.of("provider_code", "model-431c4581ab84-provider")), List.of(Map.of("model_code", "model-431c4581ab84"))));
        when(pythonKnowledgeClient.ingest(anyMap())).thenReturn(Map.of(
                "status", "SUCCEEDED",
                "chunk_count", 1,
                "generated_summary", "保修期为一年",
                "generated_keywords", List.of("保修")
        ));
        CreateKnowledgeDocumentRequest request = new CreateKnowledgeDocumentRequest();
        request.setTitle("产品保修");
        request.setContent("保修期为一年");

        knowledgeService.createTextKnowledgeDocument("demo-admin", "kb_product", request);

        assertThat(scheduledTasks).hasSize(1);
        scheduledTasks.get(0).run();
        ArgumentCaptor<KnowledgeDocument> captor = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(knowledgeDocumentRepository, atLeastOnce()).save(captor.capture());
        KnowledgeDocument savedDocument = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(savedDocument.getKbCode()).isEqualTo("kb_product");
        assertThat(savedDocument.getVersion()).isEqualTo("v1");
        assertThat(savedDocument.getSourceType()).isEqualTo("TEXT");
        assertThat(savedDocument.getStatus()).isEqualTo(KnowledgeDocumentStatus.READY);
        assertThat(savedDocument.getRawContent()).isEqualTo("保修期为一年");
        assertThat(savedDocument.getRawObjectKey()).isNull();
        assertThat(savedDocument.getIndexVersion()).isEqualTo(1);
        assertThat(savedDocument.getChunkCount()).isEqualTo(1);
        verify(knowledgeObjectStorage, never()).put(any(), any(), anyLong(), any());
        ArgumentCaptor<Map<String, Object>> requestCaptor = ArgumentCaptor.forClass(Map.class);
        verify(pythonKnowledgeClient).ingest(requestCaptor.capture());
        assertThat(requestCaptor.getValue()).containsEntry("embedding_model_code", "model-431c4581ab84");
    }

    @Test
    void documentResponseIncludesRawTextContentForTextDocumentEditing() {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setDocId("doc_text");
        document.setKbCode("kb_product");
        document.setSourceType("TEXT");
        document.setTitle("产品保修");
        document.setRawContent("完整正文：产品保修期为一年，电池保修期为六个月。");
        document.setGeneratedSummary("摘要：保修期为一年。");
        document.setStatus(KnowledgeDocumentStatus.READY);

        KnowledgeDocumentResponse response = KnowledgeDocumentResponse.fromEntity(document);

        assertThat(response.getContent()).isEqualTo("完整正文：产品保修期为一年，电池保修期为六个月。");
        assertThat(response.getGeneratedSummary()).isEqualTo("摘要：保修期为一年。");
    }
}
