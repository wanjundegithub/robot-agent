package robot.agent.apicenter.service;

import java.util.List;

public record ApiSchemaValidationResult(boolean valid, String message, List<ApiSchemaValidationIssue> issues) {
}
