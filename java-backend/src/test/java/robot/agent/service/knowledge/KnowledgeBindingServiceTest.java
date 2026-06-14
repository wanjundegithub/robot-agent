package robot.agent.service.knowledge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import robot.agent.dto.request.UpdateKnowledgeBindingsRequest;
import robot.agent.dto.response.KnowledgeBindingResponse;
import robot.agent.model.KnowledgeBinding;
import robot.agent.model.KnowledgeBindingScope;
import robot.agent.repository.KnowledgeBindingRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeBindingServiceTest {

    @Mock
    private KnowledgeBindingRepository repository;

    private KnowledgeBindingService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeBindingService(repository);
    }

    @Test
    void replaceBindingsDisablesExistingAndCreatesNextVersion() {
        KnowledgeBinding existing = binding("kb_old", 3);
        when(repository.findByScopeAndTargetIdAndEnabledTrueOrderByCreatedAtAsc(KnowledgeBindingScope.SESSION, "session_1"))
                .thenReturn(List.of(existing));
        when(repository.save(any(KnowledgeBinding.class))).thenAnswer(invocation -> invocation.getArgument(0));
        UpdateKnowledgeBindingsRequest request = new UpdateKnowledgeBindingsRequest();
        request.setScope(KnowledgeBindingScope.SESSION);
        request.setTargetId("session_1");
        request.setWorkspaceId(1L);
        request.setKbCodes(List.of("kb_product", "kb_policy"));

        KnowledgeBindingResponse response = service.replaceBindings(request);

        assertThat(existing.isEnabled()).isFalse();
        assertThat(response.getBindingVersion()).isEqualTo(4);
        assertThat(response.getKbCodes()).containsExactly("kb_product", "kb_policy");
        ArgumentCaptor<KnowledgeBinding> captor = ArgumentCaptor.forClass(KnowledgeBinding.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).hasSize(3);
        assertThat(captor.getAllValues().get(1).getBindingVersion()).isEqualTo(4);
    }

    private KnowledgeBinding binding(String kbCode, int version) {
        KnowledgeBinding binding = new KnowledgeBinding();
        binding.setScope(KnowledgeBindingScope.SESSION);
        binding.setTargetId("session_1");
        binding.setWorkspaceId(1L);
        binding.setKbCode(kbCode);
        binding.setEnabled(true);
        binding.setBindingVersion(version);
        return binding;
    }
}
