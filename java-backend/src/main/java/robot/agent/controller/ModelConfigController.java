package robot.agent.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import robot.agent.dto.request.TestModelProfileRequest;
import robot.agent.dto.request.UpsertModelProfileRequest;
import robot.agent.dto.request.UpsertModelProviderRequest;
import robot.agent.dto.request.ValidateModelProviderRequest;
import robot.agent.service.ModelConfigService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/model-config")
public class ModelConfigController {

    private final ModelConfigService modelConfigService;

    public ModelConfigController(ModelConfigService modelConfigService) {
        this.modelConfigService = modelConfigService;
    }

    @GetMapping("/providers")
    public ResponseEntity<List<Map<String, Object>>> getProviders() {
        return ResponseEntity.ok(modelConfigService.getProviderConfigs());
    }

    @PostMapping("/providers")
    public ResponseEntity<Map<String, Object>> saveProvider(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody UpsertModelProviderRequest request
    ) {
        return ResponseEntity.ok(modelConfigService.saveProviderConfig(userId, request));
    }

    @PostMapping("/providers/validate-draft")
    public ResponseEntity<Map<String, Object>> validateProviderDraft(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody ValidateModelProviderRequest request
    ) {
        return ResponseEntity.ok(modelConfigService.validateProviderDraft(userId, request));
    }

    @PutMapping("/providers/{providerCode}")
    public ResponseEntity<Map<String, Object>> updateProvider(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String providerCode,
            @RequestBody UpsertModelProviderRequest request
    ) {
        return ResponseEntity.ok(modelConfigService.updateProviderConfig(userId, providerCode, request));
    }

    @PostMapping("/providers/{providerCode}/validate")
    public ResponseEntity<Map<String, Object>> validateProvider(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String providerCode,
            @RequestBody ValidateModelProviderRequest request
    ) {
        return ResponseEntity.ok(modelConfigService.validateProviderConfig(userId, providerCode, request));
    }

    @GetMapping("/profiles")
    public ResponseEntity<List<Map<String, Object>>> getProfiles() {
        return ResponseEntity.ok(modelConfigService.getModelProfiles());
    }

    @PostMapping("/profiles")
    public ResponseEntity<Map<String, Object>> saveProfile(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody UpsertModelProfileRequest request
    ) {
        return ResponseEntity.ok(modelConfigService.saveModelProfile(userId, request));
    }

    @PutMapping("/profiles/{profileCode}")
    public ResponseEntity<Map<String, Object>> updateProfile(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String profileCode,
            @RequestBody UpsertModelProfileRequest request
    ) {
        return ResponseEntity.ok(modelConfigService.updateModelProfile(userId, profileCode, request));
    }

    @PostMapping("/profiles/{profileCode}/test-chat")
    public ResponseEntity<Map<String, Object>> testProfileChat(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String profileCode,
            @RequestBody TestModelProfileRequest request
    ) {
        return ResponseEntity.ok(modelConfigService.testProfileChat(userId, profileCode, request));
    }
}
