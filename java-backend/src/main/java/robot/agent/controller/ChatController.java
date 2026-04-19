package robot.agent.controller;

import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import robot.agent.dto.request.SendMessageRequest;
import robot.agent.dto.response.SendMessageResponse;
import robot.agent.service.ExecutionService;

@RestController
@RequestMapping("/api/sessions")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ExecutionService executionService;

    public ChatController(ExecutionService executionService) {
        this.executionService = executionService;
    }

    @PostMapping("/{sessionId}/messages")
    public ResponseEntity<SendMessageResponse> sendMessage(
            @PathVariable String sessionId,
            @RequestBody SendMessageRequest request
    ) {
        log.info(
                "chat.http.sendMessage sessionId={} messageId={} userId={} workflowCode={} workflowVersion={}",
                sessionId,
                request == null ? null : request.getMessageId(),
                request == null ? null : request.getUserId(),
                request == null ? null : request.getWorkflowCode(),
                request == null ? null : request.getWorkflowVersion()
        );
        return ResponseEntity.ok(executionService.startExecution(sessionId, request));
    }
}
