package robot.agent.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import robot.agent.dto.request.UpsertWorkflowSpaceRequest;
import robot.agent.dto.response.WorkflowSpaceResponse;
import robot.agent.service.WorkflowSpaceService;

import java.util.List;

@RestController
@RequestMapping("/api/workflow-spaces")
public class WorkflowSpaceController {
    private final WorkflowSpaceService service;

    public WorkflowSpaceController(WorkflowSpaceService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<WorkflowSpaceResponse>> listSpaces() {
        return ResponseEntity.ok(service.listSpaces());
    }

    @PostMapping
    public ResponseEntity<WorkflowSpaceResponse> upsertSpace(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody UpsertWorkflowSpaceRequest request
    ) {
        return ResponseEntity.ok(service.upsertSpace(userId, request));
    }
}
