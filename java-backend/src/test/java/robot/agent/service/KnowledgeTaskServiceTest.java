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
import robot.agent.dto.response.KnowledgeTaskResponse;
import robot.agent.model.KnowledgeBase;
import robot.agent.model.KnowledgeDocument;
import robot.agent.model.KnowledgeDocumentStatus;
import robot.agent.model.KnowledgeTask;
import robot.agent.model.KnowledgeTaskStatus;
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
class KnowledgeTaskServiceTest {

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
    void createTextKnowledgeDocumentCreatesTaskAndMarksDocumentReadyAfterPythonIngest() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setWorkspaceId(1L);
        knowledgeBase.setKbCode("kb_product");
        knowledgeBase.setCurrentVersion("v1");
        knowledgeBase.setEmbeddingModel("embedding-qwen3-8b");
        when(knowledgeBaseRepository.findByKbCode("kb_product")).thenReturn(Optional.of(knowledgeBase));
        when(knowledgeDocumentRepository.save(any(KnowledgeDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(knowledgeTaskRepository.save(any(KnowledgeTask.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(modelConfigService.buildRuntimeBundleForModel("embedding-qwen3-8b"))
                .thenReturn(new ModelConfigService.RuntimeModelBundle(List.of(Map.of("provider_code", "modelscope-embedding")), List.of(Map.of("model_code", "embedding-qwen3-8b"))));
        when(pythonKnowledgeClient.ingest(anyMap())).thenReturn(Map.of(
                "status", "SUCCEEDED",
                "chunk_count", 2,
                "generated_summary", "Warranty lasts one year.",
                "generated_keywords", List.of("warranty")
        ));
        CreateKnowledgeDocumentRequest request = new CreateKnowledgeDocumentRequest();
        request.setTitle("Product Manual");
        request.setContent("Warranty lasts one year.");

        KnowledgeDocumentResponse response = knowledgeService.createTextKnowledgeDocument("demo-admin", "kb_product", request);

        assertThat(response.getStatus()).isEqualTo(KnowledgeDocumentStatus.READY);
        assertThat(response.getChunkCount()).isEqualTo(2);
        ArgumentCaptor<KnowledgeTask> taskCaptor = ArgumentCaptor.forClass(KnowledgeTask.class);
        verify(knowledgeTaskRepository, atLeastOnce()).save(taskCaptor.capture());
        assertThat(taskCaptor.getAllValues()).anySatisfy(task -> {
            assertThat(task.getDocId()).startsWith("doc_");
            assertThat(task.getKbCode()).isEqualTo("kb_product");
            assertThat(task.getStatus()).isEqualTo(KnowledgeTaskStatus.SUCCEEDED);
        });
        verify(pythonKnowledgeClient).ingest(anyMap());
    }

    @Test
    void getKnowledgeTaskReturnsTaskResponse() {
        KnowledgeTask task = new KnowledgeTask();
        task.setTaskId("task_001");
        task.setDocId("doc_001");
        task.setKbCode("kb_product");
        task.setStatus(KnowledgeTaskStatus.SUCCEEDED);
        when(knowledgeTaskRepository.findByTaskId("task_001")).thenReturn(Optional.of(task));

        KnowledgeTaskResponse response = knowledgeService.getKnowledgeTask("task_001");

        assertThat(response.getTaskId()).isEqualTo("task_001");
        assertThat(response.getStatus()).isEqualTo(KnowledgeTaskStatus.SUCCEEDED);
    }
}
