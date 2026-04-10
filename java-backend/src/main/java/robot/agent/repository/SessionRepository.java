package robot.agent.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import robot.agent.model.Session;
import robot.agent.model.SessionStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SessionRepository extends JpaRepository<Session, String> {
    List<Session> findByUserIdAndStatusOrderByLastActivityAtDesc(String userId, SessionStatus status);
    List<Session> findByStatusAndExpiresAtBefore(SessionStatus status, LocalDateTime time);
}
