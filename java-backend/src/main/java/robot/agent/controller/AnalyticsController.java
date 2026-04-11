package robot.agent.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import robot.agent.service.AnalyticsService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/analytics/dashboard")
    public ResponseEntity<Map<String, Object>> getDashboard(@RequestParam(required = false) String sessionId) {
        return ResponseEntity.ok(analyticsService.buildDashboard(sessionId));
    }

    @GetMapping("/analytics/cost-alerts")
    public ResponseEntity<List<Map<String, Object>>> getCostAlerts(@RequestParam(required = false) String sessionId) {
        return ResponseEntity.ok(analyticsService.buildCostAlerts(sessionId));
    }

    @GetMapping("/executions/{executionId}/replay")
    public ResponseEntity<Map<String, Object>> getExecutionReplay(@PathVariable String executionId) {
        return ResponseEntity.ok(analyticsService.buildReplay(executionId));
    }

    @GetMapping("/workflows/{workflowCode}/subflow-recommendations")
    public ResponseEntity<Map<String, Object>> getSubflowRecommendations(
            @PathVariable String workflowCode,
            @RequestParam String message
    ) {
        return ResponseEntity.ok(analyticsService.recommendSubflows(workflowCode, message));
    }

    @PostMapping("/evaluations/rag")
    public ResponseEntity<Map<String, Object>> evaluateRag(@RequestBody(required = false) Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dataset = request == null ? null : (List<Map<String, Object>>) request.get("dataset");
        return ResponseEntity.ok(analyticsService.evaluateRag(dataset));
    }
}
