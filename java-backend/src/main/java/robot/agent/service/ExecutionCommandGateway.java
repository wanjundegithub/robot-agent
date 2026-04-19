package robot.agent.service;

import robot.agent.dto.request.FormSubmitRequest;
import robot.agent.dto.request.SendMessageRequest;
import robot.agent.dto.response.FormSubmitResponse;
import robot.agent.dto.response.ResumeExecutionResponse;
import robot.agent.dto.response.SendMessageResponse;

public interface ExecutionCommandGateway {
    SendMessageResponse startExecution(String sessionId, SendMessageRequest request);
    FormSubmitResponse submitForm(String executionId, FormSubmitRequest request);
    ResumeExecutionResponse resumeExecution(String executionId);
}
