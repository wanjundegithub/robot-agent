package robot.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import robot.agent.model.CapabilityAuditRecord;
import robot.agent.repository.CapabilityAuditRecordRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional
public class CapabilityAuditService {

    private final CapabilityAuditRecordRepository auditRecordRepository;
    private final ObjectMapper objectMapper;

    public CapabilityAuditService(
            CapabilityAuditRecordRepository auditRecordRepository,
            ObjectMapper objectMapper
    ) {
        this.auditRecordRepository = auditRecordRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getCapabilityAuditRecords(String groupCode) {
        return auditRecordRepository.findByGroupCodeOrderByCreatedAtDesc(groupCode).stream()
                .map(this::toAuditSummary)
                .toList();
    }

    public void recordToolReturn(Map<String, Object> payload) {
        String groupCode = stringValue(payload.get("group_code"));
        String capabilityCode = stringValue(payload.get("capability_code"));
        if (groupCode == null || capabilityCode == null) {
            return;
        }

        CapabilityAuditRecord record = new CapabilityAuditRecord();
        record.setGroupCode(groupCode);
        record.setGroupSnapshotVersion(stringValue(payload.get("group_snapshot_version")));
        record.setCapabilityCode(capabilityCode);
        record.setCapabilityVersion(stringValue(payload.get("capability_version")));
        record.setCapabilityType(stringValue(payload.get("capability_type")));
        record.setExecutionId(stringValue(payload.get("execution_id")));
        record.setNodeId(stringValue(payload.get("node_id")));
        record.setToolCode(stringValue(payload.get("tool_code")));
        record.setStatus(firstNonBlank(stringValue(payload.get("status")), "completed"));
        record.setRequestPayload(writeJson(payload.get("params")));
        record.setResponsePayload(writeJson(payload.get("output")));
        record.setErrorMessage(stringValue(payload.get("error")));
        auditRecordRepository.save(record);
    }

    public void deleteByGroupCode(String groupCode) {
        auditRecordRepository.deleteByGroupCode(groupCode);
    }

    public void deleteByCapabilityCode(String capabilityCode) {
        auditRecordRepository.deleteByCapabilityCode(capabilityCode);
    }

    private Map<String, Object> toAuditSummary(CapabilityAuditRecord record) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", record.getId());
        result.put("groupCode", record.getGroupCode());
        result.put("groupSnapshotVersion", record.getGroupSnapshotVersion());
        result.put("capabilityCode", record.getCapabilityCode());
        result.put("capabilityVersion", record.getCapabilityVersion());
        result.put("capabilityType", record.getCapabilityType());
        result.put("executionId", record.getExecutionId());
        result.put("nodeId", record.getNodeId());
        result.put("toolCode", record.getToolCode());
        result.put("status", record.getStatus());
        result.put("requestPayload", record.getRequestPayload());
        result.put("responsePayload", record.getResponsePayload());
        result.put("errorMessage", record.getErrorMessage());
        result.put("durationMs", record.getDurationMs());
        result.put("createdAt", record.getCreatedAt());
        return result;
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return null;
        }
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String firstNonBlank(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary : fallback;
    }
}
