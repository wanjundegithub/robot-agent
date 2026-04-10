package robot.agent.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import robot.agent.dto.request.CreateWorkflowRequest;
import robot.agent.dto.request.CreateWorkflowVersionRequest;
import robot.agent.dto.request.RollbackWorkflowRequest;
import robot.agent.dto.response.WorkflowResponse;
import robot.agent.dto.response.WorkflowVersionResponse;
import robot.agent.service.WorkflowService;
import java.util.List;

@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final WorkflowService workflowService;

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @GetMapping
    public ResponseEntity<List<WorkflowResponse>> getAllWorkflows() {
        return ResponseEntity.ok(workflowService.getAllWorkflows());
    }

    @PostMapping
    public ResponseEntity<WorkflowResponse> createWorkflow(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody CreateWorkflowRequest request
    ) {
        return ResponseEntity.ok(
                workflowService.createWorkflow(
                        userId,
                        request.getWorkflowCode(),
                        request.getName(),
                        request.getDescription(),
                        request.getWorkspaceId()
                )
        );
    }

    @GetMapping("/published")
    public ResponseEntity<List<WorkflowResponse>> getPublishedWorkflows() {
        return ResponseEntity.ok(workflowService.getPublishedWorkflows());
    }

    @GetMapping("/{code}")
    public ResponseEntity<WorkflowResponse> getWorkflow(@PathVariable String code) {
        return ResponseEntity.ok(workflowService.getWorkflowByCode(code));
    }

    @PostMapping("/{code}/publish")
    public ResponseEntity<WorkflowResponse> publishWorkflow(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String code,
            @RequestBody PublishRequest request
    ) {
        return ResponseEntity.ok(workflowService.publishWorkflow(userId, code, request.getVersion()));
    }

    @PostMapping("/{code}/rollback")
    public ResponseEntity<WorkflowResponse> rollbackWorkflow(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String code,
            @RequestBody RollbackWorkflowRequest request
    ) {
        return ResponseEntity.ok(workflowService.rollbackWorkflow(userId, code, request.getVersion()));
    }

    @GetMapping("/{code}/versions")
    public ResponseEntity<List<WorkflowVersionResponse>> getVersions(@PathVariable String code) {
        return ResponseEntity.ok(workflowService.getWorkflowVersions(code));
    }

    @PostMapping("/{code}/versions")
    public ResponseEntity<WorkflowVersionResponse> createVersion(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String code,
            @RequestBody CreateWorkflowVersionRequest request
    ) {
        return ResponseEntity.ok(
                workflowService.createWorkflowVersion(userId, code, request)
        );
    }

    static class PublishRequest {
        private String version;

        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
    }
}
