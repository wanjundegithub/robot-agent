package robot.agent.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import robot.agent.model.WorkflowVersion;
import robot.agent.model.WorkflowVersionStatus;
import java.util.List;
import java.util.Optional;

public interface WorkflowVersionRepository extends JpaRepository<WorkflowVersion, Long> {
    List<WorkflowVersion> findByWorkflowCodeOrderByCreatedAtDesc(String workflowCode);
    List<WorkflowVersion> findByWorkflowCodeAndStatusNotOrderByCreatedAtDesc(String workflowCode, WorkflowVersionStatus status);
    Optional<WorkflowVersion> findByWorkflowCodeAndVersion(String workflowCode, String version);
    List<WorkflowVersion> findByStatusOrderByCreatedAtDesc(WorkflowVersionStatus status);
}
