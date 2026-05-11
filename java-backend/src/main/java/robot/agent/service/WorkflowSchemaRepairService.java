package robot.agent.service;

import org.springframework.stereotype.Service;
import robot.agent.mapper.WorkflowSchemaMapper;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class WorkflowSchemaRepairService {

    private final WorkflowSchemaMapper workflowSchemaMapper;

    public WorkflowSchemaRepairService(WorkflowSchemaMapper workflowSchemaMapper) {
        this.workflowSchemaMapper = workflowSchemaMapper;
    }

    public void ensureArchivedWorkflowStatusSupported() {
        List<Map<String, Object>> columns = workflowSchemaMapper.findWorkflowStatusColumns();
        if (columns.isEmpty()) {
            return;
        }

        Object typeValue = columns.getFirst().get("Type");
        String type = typeValue == null ? "" : String.valueOf(typeValue).toLowerCase(Locale.ROOT);
        if (!type.startsWith("enum(") || type.contains("'archived'")) {
            return;
        }

        workflowSchemaMapper.supportArchivedWorkflowStatus();
    }

    public void ensureWorkflowSnapshotColumnSupported() {
        List<Map<String, Object>> columns = workflowSchemaMapper.findWorkflowSnapshotColumns();
        if (!columns.isEmpty()) {
            return;
        }
        workflowSchemaMapper.addWorkflowSnapshotColumn();
    }
}
