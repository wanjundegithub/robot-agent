package robot.agent.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import robot.agent.dto.request.CreateKnowledgeBaseRequest;
import robot.agent.dto.request.CreateKnowledgeVersionRequest;
import robot.agent.dto.response.KnowledgeBaseResponse;
import robot.agent.dto.response.KnowledgeVersionResponse;
import robot.agent.service.KnowledgeService;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @GetMapping
    public ResponseEntity<List<KnowledgeBaseResponse>> getKnowledgeBases(
            @RequestParam(required = false) Long workspaceId
    ) {
        return ResponseEntity.ok(knowledgeService.getKnowledgeBases(workspaceId));
    }

    @PostMapping
    public ResponseEntity<KnowledgeBaseResponse> createKnowledgeBase(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody CreateKnowledgeBaseRequest request
    ) {
        return ResponseEntity.ok(knowledgeService.createKnowledgeBase(userId, request));
    }

    @GetMapping("/{kbCode}/versions")
    public ResponseEntity<List<KnowledgeVersionResponse>> getKnowledgeVersions(@PathVariable String kbCode) {
        return ResponseEntity.ok(knowledgeService.getKnowledgeVersions(kbCode));
    }

    @PostMapping("/{kbCode}/versions")
    public ResponseEntity<KnowledgeVersionResponse> createKnowledgeVersion(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String kbCode,
            @RequestBody CreateKnowledgeVersionRequest request
    ) {
        return ResponseEntity.ok(knowledgeService.createKnowledgeVersion(userId, kbCode, request));
    }

    @PostMapping("/{kbCode}/versions/{version}/publish")
    public ResponseEntity<KnowledgeVersionResponse> publishKnowledgeVersion(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String kbCode,
            @PathVariable String version
    ) {
        return ResponseEntity.ok(knowledgeService.publishKnowledgeVersion(userId, kbCode, version));
    }
}
