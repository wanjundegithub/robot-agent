package robot.agent.service;

import robot.agent.dto.request.CreateWorkflowVersionRequest;
import robot.agent.dto.response.WorkflowVersionResponse;

import java.util.List;
import java.util.Map;

public interface WorkflowDraftGateway {
    WorkflowVersionResponse saveWorkflowDraft(String userId, String workflowCode, CreateWorkflowVersionRequest request);
    List<Map<String, Object>> validateWorkflowDefinition(String definitionJson, String configJson);
}
