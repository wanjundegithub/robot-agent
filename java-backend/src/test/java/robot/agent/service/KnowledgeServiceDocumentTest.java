package robot.agent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import robot.agent.config.KnowledgeProperties;
import robot.agent.dto.request.CreateKnowledgeDocumentRequest;
import robot.agent.model.KnowledgeBase;
import robot.agent.model.KnowledgeDocument;
import robot.agent.model.KnowledgeDocumentStatus;
import robot.agent.repository.KnowledgeBaseRepository;
import robot.agent.repository.KnowledgeDocumentRepository;
import robot.agent.repository.KnowledgeVersionRepository;
import robot.agent.service.knowledge.KnowledgeObjectStorage;
import robot.agent.service.knowledge.SafeObjectKeyFactory;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
    private AccessControlService accessControlService;

    @Mock
    private AuditService auditService;

    @Mock
    private KnowledgeObjectStorage knowledgeObjectStorage;

    private KnowledgeService knowledgeService;

    @BeforeEach
    void setUp() {
        knowledgeService = new KnowledgeService(
                knowledgeBaseRepository,
                knowledgeVersionRepository,
                knowledgeDocumentRepository,
                accessControlService,
                auditService,
                knowledgeObjectStorage,
                new SafeObjectKeyFactory(),
                new KnowledgeProperties()
        );
    }

    @Test
    void createTextKnowledgeDocumentStoresShortContentInMetadata() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setWorkspaceId(1L);
        knowledgeBase.setKbCode("kb_product");
        knowledgeBase.setCurrentVersion("v1");
        when(knowledgeBaseRepository.findByKbCode("kb_product")).thenReturn(Optional.of(knowledgeBase));
        when(knowledgeDocumentRepository.save(any(KnowledgeDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        CreateKnowledgeDocumentRequest request = new CreateKnowledgeDocumentRequest();
        request.setTitle("产品保修");
        request.setContent("保修期为一年");

        knowledgeService.createTextKnowledgeDocument("demo-admin", "kb_product", request);

        ArgumentCaptor<KnowledgeDocument> captor = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(knowledgeDocumentRepository).save(captor.capture());
        KnowledgeDocument savedDocument = captor.getValue();
        assertThat(savedDocument.getKbCode()).isEqualTo("kb_product");
        assertThat(savedDocument.getVersion()).isEqualTo("v1");
        assertThat(savedDocument.getSourceType()).isEqualTo("TEXT");
        assertThat(savedDocument.getStatus()).isEqualTo(KnowledgeDocumentStatus.PENDING);
        assertThat(savedDocument.getRawContent()).isEqualTo("保修期为一年");
        assertThat(savedDocument.getRawObjectKey()).isNull();
        assertThat(savedDocument.getIndexVersion()).isEqualTo(1);
        verify(knowledgeObjectStorage, never()).put(any(), any(), anyLong(), any());
    }
}
