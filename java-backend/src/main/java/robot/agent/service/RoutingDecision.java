package robot.agent.service;

import java.util.List;

public record RoutingDecision(
        String decision,
        String workflowCode,
        String workflowVersion,
        double confidence,
        double threshold,
        String thresholdSource,
        String reason,
        List<String> candidateWorkflows,
        int priority,
        String intentCode,
        String targetType,
        String targetCode,
        String clarificationQuestion,
        List<IntentCandidate> intentCandidateQueue
) {
    public record IntentCandidate(
            String intentCode,
            String targetType,
            String targetCode,
            double confidence,
            String source,
            String evidence
    ) {
    }

    public boolean isSwitchRequired() {
        return "switch_required".equalsIgnoreCase(decision);
    }
}
