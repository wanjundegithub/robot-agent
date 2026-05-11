package robot.agent.service;

import org.springframework.stereotype.Service;
import robot.agent.mapper.SessionMapper;

import java.util.List;
import java.util.Map;
import java.util.Locale;

@Service
public class SessionSchemaRepairService {

    private final SessionMapper sessionMapper;

    public SessionSchemaRepairService(SessionMapper sessionMapper) {
        this.sessionMapper = sessionMapper;
    }

    public void ensureDeletedStatusSupported() {
        List<Map<String, Object>> columns = sessionMapper.findStatusColumns();
        if (columns.isEmpty()) {
            return;
        }

        Object typeValue = columns.getFirst().get("Type");
        String type = typeValue == null ? "" : String.valueOf(typeValue).toLowerCase(Locale.ROOT);
        if (!type.startsWith("enum(") || type.contains("'deleted'")) {
            return;
        }

        sessionMapper.supportDeletedStatus();
    }
}
