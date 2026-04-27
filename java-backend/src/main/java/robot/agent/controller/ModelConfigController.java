package robot.agent.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import robot.agent.dto.request.TestModelRecordRequest;
import robot.agent.dto.request.UpsertModelProviderRequest;
import robot.agent.dto.request.UpsertModelRecordRequest;
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

    @PutMapping("/providers/{providerCode}")
    public ResponseEntity<Map<String, Object>> updateProvider(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String providerCode,
            @RequestBody UpsertModelProviderRequest request
    ) {
        return ResponseEntity.ok(modelConfigService.updateProviderConfig(userId, providerCode, request));
    }

    @DeleteMapping("/providers/{providerCode}")
    public ResponseEntity<Void> deleteProvider(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String providerCode
    ) {
        modelConfigService.deleteProviderConfig(userId, providerCode);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/providers/{providerCode}/validate")
    public ResponseEntity<Map<String, Object>> validateProvider(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String providerCode,
            @RequestBody ValidateModelProviderRequest request
    ) {
        return ResponseEntity.ok(modelConfigService.validateProviderConfig(userId, providerCode, request));
    }

    @PostMapping("/providers/validate-draft")
    public ResponseEntity<Map<String, Object>> validateProviderDraft(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody ValidateModelProviderRequest request
    ) {
        return ResponseEntity.ok(modelConfigService.validateProviderDraft(userId, request));
    }

    @GetMapping("/models")
    public ResponseEntity<Map<String, Object>> getModels(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "providerCode", required = false) String providerCode,
            @RequestParam(value = "enabled", required = false) Boolean enabled,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize
    ) {
        return ResponseEntity.ok(modelConfigService.getModelRecords(keyword, providerCode, enabled, page, pageSize));
    }

    @GetMapping("/models/{modelCode}")
    public ResponseEntity<Map<String, Object>> getModel(
            @PathVariable String modelCode
    ) {
        return ResponseEntity.ok(modelConfigService.getModelRecord(modelCode));
    }

    @PostMapping("/models")
    public ResponseEntity<Map<String, Object>> saveModelRecord(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody UpsertModelRecordRequest request
    ) {
        return ResponseEntity.ok(modelConfigService.saveModelRecord(userId, request));
    }

    @PutMapping("/models/{modelCode}")
    public ResponseEntity<Map<String, Object>> updateModelRecord(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String modelCode,
            @RequestBody UpsertModelRecordRequest request
    ) {
        return ResponseEntity.ok(modelConfigService.updateModelRecord(userId, modelCode, request));
    }

    @DeleteMapping("/models/{modelCode}")
    public ResponseEntity<Void> deleteModelRecord(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String modelCode
    ) {
        modelConfigService.deleteModelRecord(userId, modelCode);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/models/{modelCode}/validate")
    public ResponseEntity<Map<String, Object>> validateModelRecord(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String modelCode
    ) {
        return ResponseEntity.ok(modelConfigService.validateModelRecord(userId, modelCode));
    }

    @PostMapping("/models/{modelCode}/test-chat")
    public ResponseEntity<Map<String, Object>> testModelRecord(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String modelCode,
            @RequestBody TestModelRecordRequest request
    ) {
        return ResponseEntity.ok(modelConfigService.testModelRecordChat(userId, modelCode, request));
    }
}
