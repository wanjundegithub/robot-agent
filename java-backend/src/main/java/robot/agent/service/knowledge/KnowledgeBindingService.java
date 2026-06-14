package robot.agent.service.knowledge;

import org.springframework.stereotype.Service;
import robot.agent.dto.request.UpdateKnowledgeBindingsRequest;
import robot.agent.dto.response.KnowledgeBindingResponse;
import robot.agent.model.KnowledgeBinding;
import robot.agent.model.KnowledgeBindingScope;
import robot.agent.repository.KnowledgeBindingRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class KnowledgeBindingService {
    private final KnowledgeBindingRepository repository;

    public KnowledgeBindingService(KnowledgeBindingRepository repository) {
        this.repository = repository;
    }

    public KnowledgeBindingResponse replaceBindings(UpdateKnowledgeBindingsRequest request) {
        List<KnowledgeBinding> existing = repository.findByScopeAndTargetIdAndEnabledTrueOrderByCreatedAtAsc(request.getScope(), request.getTargetId());
        int nextVersion = existing.stream().map(KnowledgeBinding::getBindingVersion).mapToInt(value -> value == null ? 1 : value).max().orElse(0) + 1;
        for (KnowledgeBinding binding : existing) {
            binding.setEnabled(false);
            binding.setUpdatedAt(LocalDateTime.now());
            repository.save(binding);
        }

        List<KnowledgeBinding> saved = new ArrayList<>();
        for (String kbCode : request.getKbCodes()) {
            KnowledgeBinding binding = new KnowledgeBinding();
            binding.setScope(request.getScope());
            binding.setTargetId(request.getTargetId());
            binding.setWorkspaceId(request.getWorkspaceId());
            binding.setKbCode(kbCode);
            binding.setEnabled(true);
            binding.setBindingVersion(nextVersion);
            binding.setUpdatedAt(LocalDateTime.now());
            saved.add(repository.save(binding));
        }
        return KnowledgeBindingResponse.fromBindings(saved);
    }

    public List<KnowledgeBinding> getBindings(KnowledgeBindingScope scope, String targetId) {
        return repository.findByScopeAndTargetIdAndEnabledTrueOrderByCreatedAtAsc(scope, targetId);
    }
}
