package robot.agent.service;

import org.springframework.stereotype.Service;
import robot.agent.dto.request.CreateWorkflowVersionRequest;
import robot.agent.dto.response.WorkflowVersionResponse;

import java.util.List;
import java.util.Map;

@Service
public class WorkflowDraftGatewayService implements WorkflowDraftGateway {

    private final WorkflowService workflowService;

    public WorkflowDraftGatewayService(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @Override
    public WorkflowVersionResponse saveWorkflowDraft(String userId, String workflowCode, CreateWorkflowVersionRequest request) {
        return workflowService.saveWorkflowDraft(userId, workflowCode, request);
    }

    @Override
    public List<Map<String, Object>> validateWorkflowDefinition(String definitionJson, String configJson) {
        return workflowService.validateWorkflowDefinition(definitionJson, configJson);
    }
}
