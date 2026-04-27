package robot.agent.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import robot.agent.model.CapabilityTestRecord;

import java.util.List;

public interface CapabilityTestRecordRepository extends JpaRepository<CapabilityTestRecord, Long> {
    List<CapabilityTestRecord> findByGroupCodeOrderByCreatedAtDesc(String groupCode);
    void deleteByGroupCode(String groupCode);
    void deleteByCapabilityCode(String capabilityCode);
}
