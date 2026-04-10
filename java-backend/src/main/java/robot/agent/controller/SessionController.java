package robot.agent.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import robot.agent.dto.request.CreateSessionRequest;
import robot.agent.dto.response.SessionResponse;
import robot.agent.service.SessionService;
import java.util.List;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    public ResponseEntity<SessionResponse> createSession(@RequestBody CreateSessionRequest request) {
        return ResponseEntity.ok(sessionService.createSession(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SessionResponse> getSession(@PathVariable String id) {
        return ResponseEntity.ok(sessionService.getSession(id));
    }

    @GetMapping
    public ResponseEntity<List<SessionResponse>> getSessionsByUserId(@RequestParam String userId) {
        return ResponseEntity.ok(sessionService.getSessionsByUserId(userId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SessionResponse> updateSession(@PathVariable String id, @RequestBody CreateSessionRequest request) {
        return ResponseEntity.ok(sessionService.updateSession(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> closeSession(@PathVariable String id) {
        sessionService.closeSession(id);
        return ResponseEntity.ok().build();
    }
}
