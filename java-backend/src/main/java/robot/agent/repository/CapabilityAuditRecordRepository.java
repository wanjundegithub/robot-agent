package robot.agent.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import robot.agent.model.CapabilityAuditRecord;

import java.util.List;

public interface CapabilityAuditRecordRepository extends JpaRepository<CapabilityAuditRecord, Long> {
    List<CapabilityAuditRecord> findByGroupCodeOrderByCreatedAtDesc(String groupCode);
    void deleteByGroupCode(String groupCode);
    void deleteByCapabilityCode(String capabilityCode);
}
