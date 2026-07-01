package robot.agent.controller;

import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import robot.agent.dto.request.CreateKnowledgeBaseRequest;
import robot.agent.dto.request.CreateKnowledgeDocumentRequest;
import robot.agent.dto.request.CreateKnowledgeVersionRequest;
import robot.agent.dto.request.KnowledgeSearchRequest;
import robot.agent.dto.response.KnowledgeBaseResponse;
import robot.agent.dto.response.KnowledgeDocumentResponse;
import robot.agent.dto.response.KnowledgeSearchResponse;
import robot.agent.dto.response.KnowledgeSearchStreamEvent;
import robot.agent.dto.response.KnowledgeTaskResponse;
import robot.agent.dto.response.KnowledgeVersionResponse;
import robot.agent.service.KnowledgeService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeController {
    private static final long SEARCH_STREAM_TIMEOUT_MS = 60_000L;
    private static final int SEARCH_STREAM_FIRST_FRAME_DEADLINE_MS = 1_000;
    private static final int SEARCH_STREAM_DELTA_CHARS = 6;
    private static final String SEARCH_STREAM_STARTED_CONTENT = "检索中";
    private static final String SEARCH_STREAM_EMPTY_CONTENT = "未命中";

    private final KnowledgeService knowledgeService;
    private final TaskExecutor taskExecutor;

    public KnowledgeController(KnowledgeService knowledgeService, TaskExecutor taskExecutor) {
        this.knowledgeService = knowledgeService;
        this.taskExecutor = taskExecutor;
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

    @PutMapping("/{kbCode}")
    public ResponseEntity<KnowledgeBaseResponse> updateKnowledgeBase(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String kbCode,
            @RequestBody CreateKnowledgeBaseRequest request
    ) {
        return ResponseEntity.ok(knowledgeService.updateKnowledgeBase(userId, kbCode, request));
    }

    @DeleteMapping("/{kbCode}")
    public ResponseEntity<Void> deleteKnowledgeBase(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String kbCode
    ) {
        knowledgeService.deleteKnowledgeBase(userId, kbCode);
        return ResponseEntity.noContent().build();
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

    @PutMapping("/documents/{docId}")
    public ResponseEntity<KnowledgeDocumentResponse> updateKnowledgeDocument(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String docId,
            @RequestBody CreateKnowledgeDocumentRequest request
    ) {
        return ResponseEntity.ok(knowledgeService.updateKnowledgeDocument(userId, docId, request));
    }

    @DeleteMapping("/documents/{docId}")
    public ResponseEntity<Void> deleteKnowledgeDocument(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String docId
    ) {
        knowledgeService.deleteKnowledgeDocument(userId, docId);
        return ResponseEntity.noContent().build();
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

    @DeleteMapping("/tasks/{taskId}")
    public ResponseEntity<Void> deleteKnowledgeTask(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String taskId
    ) {
        knowledgeService.deleteKnowledgeTask(userId, taskId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/search")
    public ResponseEntity<KnowledgeSearchResponse> searchKnowledge(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody KnowledgeSearchRequest request
    ) {
        return ResponseEntity.ok(knowledgeService.searchKnowledge(userId, request));
    }

    @PostMapping(value = "/search/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSearchKnowledge(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody KnowledgeSearchRequest request
    ) {
        return streamSearch(userId, request);
    }

    @PostMapping("/{kbCode}/search")
    public ResponseEntity<KnowledgeSearchResponse> searchSingleKnowledgeBase(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String kbCode,
            @RequestBody KnowledgeSearchRequest request
    ) {
        request.setKbCodes(List.of(kbCode));
        return ResponseEntity.ok(knowledgeService.searchKnowledge(userId, request));
    }

    @PostMapping(value = "/{kbCode}/search/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSingleKnowledgeBaseSearch(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String kbCode,
            @RequestBody KnowledgeSearchRequest request
    ) {
        request.setKbCodes(List.of(kbCode));
        return streamSearch(userId, request);
    }

    private SseEmitter streamSearch(String userId, KnowledgeSearchRequest request) {
        SseEmitter emitter = new SseEmitter(SEARCH_STREAM_TIMEOUT_MS);
        long startedAtNanos = System.nanoTime();
        try {
            sendStreamEvent(
                    emitter,
                    "started",
                    KnowledgeSearchStreamEvent.started(
                            request == null ? null : request.getQuery(),
                            request == null ? List.of() : request.getKbCodes(),
                            SEARCH_STREAM_FIRST_FRAME_DEADLINE_MS,
                            SEARCH_STREAM_STARTED_CONTENT
                    )
            );
        } catch (IOException exc) {
            emitter.completeWithError(exc);
            return emitter;
        }

        Runnable searchTask = () -> {
            try {
                KnowledgeSearchResponse response = knowledgeService.searchKnowledge(userId, request);
                streamDeltaEvents(emitter, response, startedAtNanos);
                sendStreamEvent(
                        emitter,
                        "completed",
                        KnowledgeSearchStreamEvent.completed(response, elapsedMs(startedAtNanos))
                );
                emitter.complete();
            } catch (Exception exc) {
                try {
                    sendStreamEvent(
                            emitter,
                            "failed",
                            KnowledgeSearchStreamEvent.failed("Knowledge search failed", elapsedMs(startedAtNanos))
                    );
                    emitter.complete();
                } catch (IOException sendError) {
                    emitter.completeWithError(sendError);
                }
            }
        };
        try {
            taskExecutor.execute(searchTask);
        } catch (RuntimeException exc) {
            try {
                sendStreamEvent(
                        emitter,
                        "failed",
                        KnowledgeSearchStreamEvent.failed("Knowledge search failed", elapsedMs(startedAtNanos))
                );
                emitter.complete();
            } catch (IOException sendError) {
                emitter.completeWithError(sendError);
            }
        }
        return emitter;
    }

    private void sendStreamEvent(SseEmitter emitter, String eventName, KnowledgeSearchStreamEvent event) throws IOException {
        emitter.send(SseEmitter.event().name(eventName).data(event, MediaType.APPLICATION_JSON));
    }

    private void streamDeltaEvents(SseEmitter emitter, KnowledgeSearchResponse response, long startedAtNanos) throws IOException {
        String content = streamContent(response);
        List<String> chunks = splitByCodePoint(content, SEARCH_STREAM_DELTA_CHARS);
        for (int index = 0; index < chunks.size(); index++) {
            sendStreamEvent(
                    emitter,
                    "delta",
                    KnowledgeSearchStreamEvent.delta(chunks.get(index), index + 1, elapsedMs(startedAtNanos))
            );
        }
    }

    private String streamContent(KnowledgeSearchResponse response) {
        if (response == null) {
            return SEARCH_STREAM_EMPTY_CONTENT;
        }
        if (response.getAnswer() != null && !response.getAnswer().isBlank()) {
            return response.getAnswer().trim();
        }
        String joinedContent = response.getDocuments().stream()
                .map(KnowledgeSearchResponse.DocumentHit::getContent)
                .filter(content -> content != null && !content.isBlank())
                .map(String::trim)
                .collect(Collectors.joining("\n"));
        return joinedContent.isBlank() ? SEARCH_STREAM_EMPTY_CONTENT : joinedContent;
    }

    private List<String> splitByCodePoint(String content, int chunkSize) {
        String source = content == null ? "" : content;
        if (source.isBlank()) {
            return List.of(SEARCH_STREAM_EMPTY_CONTENT);
        }
        int[] codePoints = source.codePoints().toArray();
        List<String> chunks = new ArrayList<>();
        for (int offset = 0; offset < codePoints.length; offset += chunkSize) {
            int length = Math.min(chunkSize, codePoints.length - offset);
            chunks.add(new String(codePoints, offset, length));
        }
        return chunks;
    }

    private long elapsedMs(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }
}
