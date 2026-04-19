package robot.agent.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import robot.agent.model.Execution;
import robot.agent.model.ExecutionStatus;
import java.util.List;
import java.util.Optional;

public interface ExecutionRepository extends JpaRepository<Execution, String> {
    Optional<Execution> findBySessionIdAndStatus(String sessionId, ExecutionStatus status);
    Optional<Execution> findBySessionIdAndClientMessageId(String sessionId, String clientMessageId);
    List<Execution> findBySessionIdOrderByCreatedAtAsc(String sessionId);
    List<Execution> findBySessionIdOrderByCreatedAtDesc(String sessionId);
}
