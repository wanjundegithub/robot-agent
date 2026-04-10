package robot.agent.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import robot.agent.model.AuditLog;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
}
