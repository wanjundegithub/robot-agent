package robot.agent.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import robot.agent.model.CapabilityGroup;

import java.util.List;
import java.util.Optional;

public interface CapabilityGroupRepository extends JpaRepository<CapabilityGroup, Long> {
    List<CapabilityGroup> findAllByOrderByUpdatedAtDesc();
    Optional<CapabilityGroup> findByGroupCode(String groupCode);
    void deleteByGroupCode(String groupCode);
}
