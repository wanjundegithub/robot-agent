package robot.agent.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import robot.agent.model.RobotBinding;
import robot.agent.model.RobotBindingType;

import java.util.List;

public interface RobotBindingRepository extends JpaRepository<RobotBinding, Long> {
    List<RobotBinding> findByRobotCodeAndEnabledTrueOrderByCreatedAtAsc(String robotCode);
    List<RobotBinding> findByRobotCodeAndEnabledTrueAndBindingTypeInOrderByCreatedAtAsc(
            String robotCode,
            List<RobotBindingType> bindingTypes
    );
}
