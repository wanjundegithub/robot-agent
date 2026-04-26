package robot.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.server.ResponseStatusException;
import robot.agent.model.LlmModelRecord;
import robot.agent.repository.LlmModelRecordRepository;
import robot.agent.repository.LlmModelProfileRepository;
import robot.agent.repository.LlmProviderConfigRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelConfigServiceTest {

    private static final LocalDateTime FIXED_UPDATED_AT = LocalDateTime.of(2026, 4, 26, 10, 30, 0);

    private final LlmProviderConfigRepository providerRepository = mock(LlmProviderConfigRepository.class);
    private final LlmModelProfileRepository profileRepository = mock(LlmModelProfileRepository.class);
    private final LlmModelRecordRepository modelRecordRepository = mock(LlmModelRecordRepository.class);
    private final AccessControlService accessControlService = mock(AccessControlService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final ModelConfigService modelConfigService = new ModelConfigService(
            providerRepository,
            profileRepository,
            modelRecordRepository,
            new ObjectMapper(),
            accessControlService,
            auditService
    );

    @BeforeEach
    void setUp() {
        doNothing().when(accessControlService).requireWorkflowAdminAction(anyString(), anyLong(), anyString(), anyString());
        doNothing().when(auditService).logAction(anyLong(), anyString(), anyString(), anyString(), anyString(), any(), anyInt());
    }

    @Test
    void listModelRecordsReturnsPagedRowsSortedByUpdatedAtDesc() {
        PageRequest pageRequest = PageRequest.of(0, 10, Sort.by(Sort.Order.desc("updatedAt")));
        when(modelRecordRepository.search("doubao", "provider-a", true, any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(modelRecord("chat-main", "Doubao Chat")), pageRequest, 1));

        Map<String, Object> page = modelConfigService.getModelRecords("doubao", "provider-a", true, 0, 10);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(modelRecordRepository).search("doubao", "provider-a", true, pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();

        assertThat(page.get("page")).isEqualTo(0);
        assertThat(page.get("page_size")).isEqualTo(10);
        assertThat(page.get("total")).isEqualTo(1L);
        assertThat(pageable.getPageNumber()).isEqualTo(0);
        assertThat(pageable.getPageSize()).isEqualTo(10);
        assertThat(pageable.getSort().getOrderFor("updatedAt")).isNotNull();
        assertThat(pageable.getSort().getOrderFor("updatedAt").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat((List<?>) page.get("items")).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) ((List<?>) page.get("items")).get(0);
        assertThat(first.get("model_code")).isEqualTo("chat-main");
        assertThat(first.get("model_name")).isEqualTo("Doubao Chat");
    }

    @Test
    void deleteProviderRejectsWhenModelRecordsStillReferenceIt() {
        when(modelRecordRepository.countByProviderCode("provider-a")).thenReturn(2L);

        assertThatThrownBy(() -> modelConfigService.deleteProviderConfig("demo-admin", "provider-a"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getReason())
                        .contains("provider is still referenced"));
    }

    private LlmModelRecord modelRecord(String modelCode, String modelName) {
        LlmModelRecord record = new LlmModelRecord();
        record.setModelCode(modelCode);
        record.setModelName(modelName);
        record.setProviderCode("provider-a");
        record.setProviderType("doubao");
        record.setEnabled(true);
        record.setUpdatedAt(FIXED_UPDATED_AT);
        return record;
    }
}
