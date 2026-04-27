package robot.agent.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import robot.agent.model.CapabilityAuthConfig;

import java.util.List;

public interface CapabilityAuthConfigRepository extends JpaRepository<CapabilityAuthConfig, Long> {
    List<CapabilityAuthConfig> findByGroupCodeOrderByUpdatedAtDesc(String groupCode);
    void deleteByGroupCode(String groupCode);
}
