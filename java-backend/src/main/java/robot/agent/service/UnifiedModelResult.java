package robot.agent.service;

import java.util.Map;

public record UnifiedModelResult(
        String modelCode,
        String providerCode,
        String upstreamModelCode,
        String text,
        Map<String, Object> usage,
        Map<String, Object> rawResponse
) {
}
