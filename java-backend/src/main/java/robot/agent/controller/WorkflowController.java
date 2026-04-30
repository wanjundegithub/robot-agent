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
import java.util.Map;

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

    @PostMapping("/{code}/versions/{version}/archive")
    public ResponseEntity<WorkflowVersionResponse> archiveVersion(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String code,
            @PathVariable String version
    ) {
        return ResponseEntity.ok(workflowService.archiveWorkflowVersion(userId, code, version));
    }

    @DeleteMapping("/{code}/versions/{version}")
    public ResponseEntity<Void> deleteVersion(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String code,
            @PathVariable String version
    ) {
        workflowService.deleteWorkflowVersion(userId, code, version);
        return ResponseEntity.noContent().build();
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

    @PostMapping("/{code}/drafts")
    public ResponseEntity<WorkflowVersionResponse> saveDraft(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String code,
            @RequestBody CreateWorkflowVersionRequest request
    ) {
        return ResponseEntity.ok(workflowService.saveWorkflowDraft(userId, code, request));
    }

    @PostMapping("/{code}/validate-draft")
    public ResponseEntity<Map<String, Object>> validateDraft(
            @PathVariable String code,
            @RequestBody ValidateDraftRequest request
    ) {
        List<Map<String, Object>> issues = workflowService.validateWorkflowDefinition(request.getDefinition(), request.getConfig());
        return ResponseEntity.ok(Map.of(
                "workflow_code", code,
                "valid", issues.isEmpty(),
                "issues", issues
        ));
    }

    static class PublishRequest {
        private String version;

        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
    }

    static class ValidateDraftRequest {
        private String definition;
        private String config;

        public String getDefinition() { return definition; }
        public void setDefinition(String definition) { this.definition = definition; }

        public String getConfig() { return config; }
        public void setConfig(String config) { this.config = config; }
    }
}
