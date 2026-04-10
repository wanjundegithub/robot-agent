package robot.agent.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import robot.agent.dto.request.SendMessageRequest;
import robot.agent.dto.response.SendMessageResponse;
import robot.agent.service.ExecutionService;

@RestController
@RequestMapping("/api/sessions")
public class ChatController {

    private final ExecutionService executionService;

    public ChatController(ExecutionService executionService) {
        this.executionService = executionService;
    }

    @PostMapping("/{sessionId}/messages")
    public ResponseEntity<SendMessageResponse> sendMessage(
            @PathVariable String sessionId,
            @RequestBody SendMessageRequest request
    ) {
        return ResponseEntity.ok(executionService.startExecution(sessionId, request));
    }
}
