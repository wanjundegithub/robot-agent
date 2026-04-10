package robot.agent.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import robot.agent.dto.request.FormSubmitRequest;
import robot.agent.dto.response.ExecutionResponse;
import robot.agent.dto.response.FormSubmitResponse;
import robot.agent.dto.response.ResumeExecutionResponse;
import robot.agent.service.ExecutionService;

import java.util.List;

@RestController
@RequestMapping("/api/executions")
public class ExecutionController {

    private final ExecutionService executionService;

    public ExecutionController(ExecutionService executionService) {
        this.executionService = executionService;
    }

    @GetMapping("/{executionId}")
    public ResponseEntity<ExecutionResponse> getExecution(@PathVariable String executionId) {
        return ResponseEntity.ok(executionService.getExecution(executionId));
    }

    @GetMapping
    public ResponseEntity<List<ExecutionResponse>> getExecutionsBySession(@RequestParam String sessionId) {
        return ResponseEntity.ok(executionService.getExecutionsBySession(sessionId));
    }

    @PostMapping("/{executionId}/form-submit")
    public ResponseEntity<FormSubmitResponse> submitForm(
            @PathVariable String executionId,
            @RequestBody FormSubmitRequest request
    ) {
        return ResponseEntity.ok(executionService.submitForm(executionId, request));
    }

    @PostMapping("/{executionId}/resume")
    public ResponseEntity<ResumeExecutionResponse> resumeExecution(@PathVariable String executionId) {
        return ResponseEntity.ok(executionService.resumeExecution(executionId));
    }
}
