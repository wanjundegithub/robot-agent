package robot.agent.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import robot.agent.model.RobotConfig;
import robot.agent.model.RobotStatus;

import java.util.List;
import java.util.Optional;

public interface RobotConfigRepository extends JpaRepository<RobotConfig, Long> {
    Optional<RobotConfig> findByRobotCode(String robotCode);
    List<RobotConfig> findByStatusNotOrderByCreatedAtDesc(RobotStatus status);
}
