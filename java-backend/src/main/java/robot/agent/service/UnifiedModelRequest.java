package robot.agent.service;

import java.util.List;
import java.util.Map;

public record UnifiedModelRequest(
        String modelCode,
        List<Map<String, Object>> messages,
        String systemPrompt,
        Map<String, Object> options,
        Integer timeoutSec,
        boolean includeRawResponse
) {
}
