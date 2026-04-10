package robot.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import robot.agent.model.AuditLog;
import robot.agent.repository.AuditLogRepository;

@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    public void logAction(
            Long workspaceId,
            String userId,
            String action,
            String resourceType,
            String resourceId,
            Object requestData,
            Integer responseStatus
    ) {
        AuditLog auditLog = new AuditLog();
        auditLog.setWorkspaceId(workspaceId == null ? 1L : workspaceId);
        auditLog.setUserId(userId);
        auditLog.setAction(action);
        auditLog.setResourceType(resourceType);
        auditLog.setResourceId(resourceId);
        auditLog.setRequestData(writeJson(requestData));
        auditLog.setResponseStatus(responseStatus);
        auditLogRepository.save(auditLog);
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return String.valueOf(value);
        }
    }
}
