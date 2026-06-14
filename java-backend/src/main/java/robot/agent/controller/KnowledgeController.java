package robot.agent.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import robot.agent.dto.request.CreateKnowledgeBaseRequest;
import robot.agent.dto.request.CreateKnowledgeDocumentRequest;
import robot.agent.dto.request.CreateKnowledgeVersionRequest;
import robot.agent.dto.response.KnowledgeBaseResponse;
import robot.agent.dto.response.KnowledgeDocumentResponse;
import robot.agent.dto.response.KnowledgeTaskResponse;
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

    @GetMapping("/{kbCode}/documents")
    public ResponseEntity<List<KnowledgeDocumentResponse>> getKnowledgeDocuments(@PathVariable String kbCode) {
        return ResponseEntity.ok(knowledgeService.getKnowledgeDocuments(kbCode));
    }

    @PostMapping("/{kbCode}/documents/text")
    public ResponseEntity<KnowledgeDocumentResponse> createTextKnowledgeDocument(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String kbCode,
            @RequestBody CreateKnowledgeDocumentRequest request
    ) {
        return ResponseEntity.ok(knowledgeService.createTextKnowledgeDocument(userId, kbCode, request));
    }

    @PostMapping("/{kbCode}/documents/files")
    public ResponseEntity<KnowledgeDocumentResponse> uploadKnowledgeDocument(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String kbCode,
            @RequestParam("file") MultipartFile file
    ) {
        return ResponseEntity.ok(knowledgeService.uploadKnowledgeDocument(userId, kbCode, file));
    }

    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<KnowledgeTaskResponse> getKnowledgeTask(@PathVariable String taskId) {
        return ResponseEntity.ok(knowledgeService.getKnowledgeTask(taskId));
    }

    @GetMapping("/documents/{docId}/tasks")
    public ResponseEntity<List<KnowledgeTaskResponse>> getDocumentTasks(@PathVariable String docId) {
        return ResponseEntity.ok(knowledgeService.getDocumentTasks(docId));
    }

    @PostMapping("/tasks/{taskId}/retry")
    public ResponseEntity<KnowledgeTaskResponse> retryKnowledgeTask(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String taskId
    ) {
        return ResponseEntity.ok(knowledgeService.retryKnowledgeTask(userId, taskId));
    }
}
