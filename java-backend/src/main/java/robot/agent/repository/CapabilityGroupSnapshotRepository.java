package robot.agent.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import robot.agent.model.CapabilityGroupSnapshot;

import java.util.List;
import java.util.Optional;

public interface CapabilityGroupSnapshotRepository extends JpaRepository<CapabilityGroupSnapshot, Long> {
    CapabilityGroupSnapshot findFirstByGroupCodeOrderByPublishedAtDesc(String groupCode);
    Optional<CapabilityGroupSnapshot> findByGroupCodeAndSnapshotVersion(String groupCode, String snapshotVersion);
    List<CapabilityGroupSnapshot> findByGroupCodeOrderByPublishedAtDesc(String groupCode);
    void deleteByGroupCode(String groupCode);
}
