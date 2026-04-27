package robot.agent.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import robot.agent.model.CapabilityItem;

import java.util.List;
import java.util.Optional;

public interface CapabilityItemRepository extends JpaRepository<CapabilityItem, Long> {
    long countByGroupCode(String groupCode);
    List<CapabilityItem> findByGroupCodeOrderByUpdatedAtDesc(String groupCode);
    Optional<CapabilityItem> findByGroupCodeAndCapabilityCode(String groupCode, String capabilityCode);
    void deleteByGroupCodeAndCapabilityCode(String groupCode, String capabilityCode);
    void deleteByGroupCode(String groupCode);
}
