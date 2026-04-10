package robot.agent.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import robot.agent.model.KnowledgeBase;

import java.util.List;
import java.util.Optional;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {
    Optional<KnowledgeBase> findByKbCode(String kbCode);

    List<KnowledgeBase> findByWorkspaceIdOrderByCreatedAtDesc(Long workspaceId);
}
