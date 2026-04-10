package robot.agent.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import robot.agent.model.ExecutionNodeLog;
import java.util.List;

public interface ExecutionNodeLogRepository extends JpaRepository<ExecutionNodeLog, Long> {
    List<ExecutionNodeLog> findByExecutionIdOrderByCreatedAtAsc(String executionId);
}
