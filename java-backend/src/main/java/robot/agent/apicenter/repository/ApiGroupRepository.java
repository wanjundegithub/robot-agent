package robot.agent.apicenter.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import robot.agent.apicenter.model.ApiGroup;

import java.util.List;

public interface ApiGroupRepository extends JpaRepository<ApiGroup, Long> {
    List<ApiGroup> findAllByOrderByUpdatedAtDesc();
}
