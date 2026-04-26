package robot.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelConfigServiceTest {

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

    @Test
    void listModelRecordsReturnsPagedRowsSortedByUpdatedAtDesc() {
        PageRequest pageRequest = PageRequest.of(0, 10);
        when(modelRecordRepository.search("doubao", "provider-a", true, pageRequest))
                .thenReturn(new PageImpl<>(List.of(modelRecord("chat-main", "Doubao Chat")), pageRequest, 1));

        Map<String, Object> page = modelConfigService.getModelRecords("doubao", "provider-a", true, 0, 10);

        assertThat(page.get("page")).isEqualTo(0);
        assertThat(page.get("page_size")).isEqualTo(10);
        assertThat(page.get("total")).isEqualTo(1L);
        assertThat((List<?>) page.get("items")).hasSize(1);
    }

    @Test
    void deleteProviderRejectsWhenModelRecordsStillReferenceIt() {
        when(modelRecordRepository.countByProviderCode("provider-a")).thenReturn(2L);

        assertThatThrownBy(() -> modelConfigService.deleteProviderConfig("demo-admin", "provider-a"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("provider is still referenced");
    }

    private LlmModelRecord modelRecord(String modelCode, String modelName) {
        LlmModelRecord record = new LlmModelRecord();
        record.setModelCode(modelCode);
        record.setModelName(modelName);
        record.setProviderCode("provider-a");
        record.setProviderType("doubao");
        record.setEnabled(true);
        record.setUpdatedAt(LocalDateTime.now());
        return record;
    }
}
