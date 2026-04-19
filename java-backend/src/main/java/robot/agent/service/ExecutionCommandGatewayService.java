package robot.agent.service;

import org.springframework.stereotype.Service;
import robot.agent.dto.request.FormSubmitRequest;
import robot.agent.dto.request.SendMessageRequest;
import robot.agent.dto.response.FormSubmitResponse;
import robot.agent.dto.response.ResumeExecutionResponse;
import robot.agent.dto.response.SendMessageResponse;

@Service
public class ExecutionCommandGatewayService implements ExecutionCommandGateway {

    private final ExecutionService executionService;

    public ExecutionCommandGatewayService(ExecutionService executionService) {
        this.executionService = executionService;
    }

    @Override
    public SendMessageResponse startExecution(String sessionId, SendMessageRequest request) {
        return executionService.startExecution(sessionId, request);
    }

    @Override
    public FormSubmitResponse submitForm(String executionId, FormSubmitRequest request) {
        return executionService.submitForm(executionId, request);
    }

    @Override
    public ResumeExecutionResponse resumeExecution(String executionId) {
        return executionService.resumeExecution(executionId);
    }
}
