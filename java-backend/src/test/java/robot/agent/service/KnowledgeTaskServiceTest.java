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
import java.util.Set;

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
        when(knowledgeBaseRepository.findByKbCode("kb_product")).thenReturn(Optional.of(knowledgeBase));
        when(knowledgeDocumentRepository.save(any(KnowledgeDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(knowledgeTaskRepository.save(any(KnowledgeTask.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(modelConfigService.buildRuntimeBundleForModel("model-431c4581ab84"))
                .thenReturn(new ModelConfigService.RuntimeModelBundle(List.of(Map.of("provider_code", "model-431c4581ab84-provider")), List.of(Map.of("model_code", "model-431c4581ab84"))));
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
        ArgumentCaptor<Map<String, Object>> ingestRequestCaptor = ArgumentCaptor.forClass(Map.class);
        verify(pythonKnowledgeClient).ingest(ingestRequestCaptor.capture());
        assertThat(ingestRequestCaptor.getValue()).containsEntry("embedding_model_code", "model-431c4581ab84");
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

    @Test
    void retryKnowledgeTaskClearsPreviousDocumentErrorAfterSuccessfulIngest() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setWorkspaceId(1L);
        knowledgeBase.setKbCode("kb_product");

        KnowledgeDocument document = new KnowledgeDocument();
        document.setDocId("doc_failed");
        document.setKbCode("kb_product");
        document.setFilename("manual.txt");
        document.setSourceType("TEXT");
        document.setRawContent("Warranty lasts one year.");
        document.setIndexVersion(1);
        document.setStatus(KnowledgeDocumentStatus.FAILED);
        document.setErrorMessage("'InMemoryKnowledgeStore' object has no attribute 'upsert_chunks'");

        KnowledgeTask task = new KnowledgeTask();
        task.setTaskId("task_failed");
        task.setDocId("doc_failed");
        task.setKbCode("kb_product");
        task.setStatus(KnowledgeTaskStatus.FAILED);
        task.setErrorMessage(document.getErrorMessage());

        when(knowledgeTaskRepository.findByTaskId("task_failed")).thenReturn(Optional.of(task));
        when(knowledgeDocumentRepository.findByDocId("doc_failed")).thenReturn(Optional.of(document));
        when(knowledgeBaseRepository.findByKbCode("kb_product")).thenReturn(Optional.of(knowledgeBase));
        when(knowledgeTaskRepository.save(any(KnowledgeTask.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(knowledgeDocumentRepository.save(any(KnowledgeDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(modelConfigService.buildRuntimeBundleForModel("model-431c4581ab84"))
                .thenReturn(new ModelConfigService.RuntimeModelBundle(List.of(Map.of("provider_code", "model-431c4581ab84-provider")), List.of(Map.of("model_code", "model-431c4581ab84"))));
        when(pythonKnowledgeClient.ingest(anyMap())).thenReturn(Map.of(
                "status", "SUCCEEDED",
                "chunk_count", 1,
                "generated_summary", "Warranty lasts one year.",
                "generated_keywords", List.of("warranty")
        ));

        KnowledgeTaskResponse response = knowledgeService.retryKnowledgeTask("demo-admin", "task_failed");

        assertThat(response.getStatus()).isEqualTo(KnowledgeTaskStatus.SUCCEEDED);
        ArgumentCaptor<KnowledgeDocument> documentCaptor = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(knowledgeDocumentRepository).save(documentCaptor.capture());
        KnowledgeDocument savedDocument = documentCaptor.getValue();
        assertThat(savedDocument.getStatus()).isEqualTo(KnowledgeDocumentStatus.READY);
        assertThat(savedDocument.getErrorMessage()).isNull();
    }

    @Test
    void deleteKnowledgeTaskRemovesOnlyTaskAfterPermissionCheck() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setWorkspaceId(1L);
        knowledgeBase.setKbCode("kb_product");

        KnowledgeTask task = new KnowledgeTask();
        task.setTaskId("task_001");
        task.setDocId("doc_001");
        task.setKbCode("kb_product");
        task.setStatus(KnowledgeTaskStatus.SUCCEEDED);

        when(knowledgeTaskRepository.findByTaskId("task_001")).thenReturn(Optional.of(task));
        when(knowledgeBaseRepository.findByKbCode("kb_product")).thenReturn(Optional.of(knowledgeBase));

        knowledgeService.deleteKnowledgeTask("demo-admin", "task_001");

        verify(accessControlService).requireAnyRole("demo-admin", 1L, Set.of("workflow_admin", "knowledge_admin"));
        verify(knowledgeTaskRepository).delete(task);
    }
}
