package robot.agent.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import robot.agent.model.WorkflowSpace;
import robot.agent.model.WorkflowStatus;

import java.util.List;
import java.util.Optional;

public interface WorkflowSpaceRepository extends JpaRepository<WorkflowSpace, Long> {
    Optional<WorkflowSpace> findBySpaceCode(String spaceCode);
    List<WorkflowSpace> findByStatusNotOrderByCreatedAtDesc(WorkflowStatus status);
}
