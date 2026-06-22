package robot.agent.service.robot;

import java.util.List;

public record RobotRuntimeContext(
        String robotCode,
        Long workspaceId,
        Integer bindingVersion,
        List<String> workflowSpaceCodes,
        List<String> kbCodes,
        String routeStrategy
) {
}
