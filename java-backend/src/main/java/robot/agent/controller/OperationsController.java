package robot.agent.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import robot.agent.service.OperationsService;

import java.util.Map;

@RestController
@RequestMapping("/api/operations")
public class OperationsController {

    private final OperationsService operationsService;

    public OperationsController(OperationsService operationsService) {
        this.operationsService = operationsService;
    }

    @GetMapping("/readiness")
    public ResponseEntity<Map<String, Object>> getReadiness(@RequestParam(required = false) String sessionId) {
        return ResponseEntity.ok(operationsService.buildReadiness(sessionId));
    }
}
