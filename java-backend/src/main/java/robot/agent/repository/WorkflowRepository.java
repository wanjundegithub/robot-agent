package robot.agent.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import robot.agent.model.Workflow;
import robot.agent.model.WorkflowStatus;
import java.util.List;
import java.util.Optional;

public interface WorkflowRepository extends JpaRepository<Workflow, Long> {
    Optional<Workflow> findByWorkflowCode(String workflowCode);
    List<Workflow> findByStatusOrderByCreatedAtDesc(WorkflowStatus status);
    List<Workflow> findByStatusNotOrderByCreatedAtDesc(WorkflowStatus status);
    List<Workflow> findByWorkflowSpaceCodeAndStatusNotOrderByCreatedAtDesc(String workflowSpaceCode, WorkflowStatus status);
}
