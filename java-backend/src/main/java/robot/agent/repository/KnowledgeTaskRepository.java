package robot.agent.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import robot.agent.model.KnowledgeTask;
import robot.agent.model.KnowledgeTaskStatus;

import java.util.List;
import java.util.Optional;

public interface KnowledgeTaskRepository extends JpaRepository<KnowledgeTask, Long> {
    Optional<KnowledgeTask> findByTaskId(String taskId);

    List<KnowledgeTask> findByDocIdOrderByCreatedAtDesc(String docId);

    List<KnowledgeTask> findByStatusOrderByCreatedAtAsc(KnowledgeTaskStatus status);
}
