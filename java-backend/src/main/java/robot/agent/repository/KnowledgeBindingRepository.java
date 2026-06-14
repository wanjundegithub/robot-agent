package robot.agent.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import robot.agent.model.KnowledgeBinding;
import robot.agent.model.KnowledgeBindingScope;

import java.util.List;

public interface KnowledgeBindingRepository extends JpaRepository<KnowledgeBinding, Long> {
    List<KnowledgeBinding> findByScopeAndTargetIdAndEnabledTrueOrderByCreatedAtAsc(KnowledgeBindingScope scope, String targetId);
}
