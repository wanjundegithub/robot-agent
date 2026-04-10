package robot.agent.service;

import java.util.List;

public record RoutingDecision(
        String decision,
        String workflowCode,
        String workflowVersion,
        double confidence,
        String reason,
        List<String> candidateWorkflows,
        int priority
) {
    public boolean isSwitchRequired() {
        return "switch_required".equalsIgnoreCase(decision);
    }
}
