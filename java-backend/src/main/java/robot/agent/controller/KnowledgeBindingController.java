package robot.agent.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import robot.agent.dto.request.UpdateKnowledgeBindingsRequest;
import robot.agent.dto.response.KnowledgeBindingResponse;
import robot.agent.model.KnowledgeBindingScope;
import robot.agent.service.knowledge.KnowledgeBindingService;

@RestController
@RequestMapping("/api/knowledge-bindings")
public class KnowledgeBindingController {
    private final KnowledgeBindingService service;

    public KnowledgeBindingController(KnowledgeBindingService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<KnowledgeBindingResponse> getBindings(
            @RequestParam KnowledgeBindingScope scope,
            @RequestParam String targetId
    ) {
        return ResponseEntity.ok(KnowledgeBindingResponse.fromBindings(service.getBindings(scope, targetId)));
    }

    @PutMapping
    public ResponseEntity<KnowledgeBindingResponse> updateBindings(@RequestBody UpdateKnowledgeBindingsRequest request) {
        return ResponseEntity.ok(service.replaceBindings(request));
    }
}
