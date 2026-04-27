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
import org.springframework.web.bind.annotation.RestController;
import robot.agent.service.CapabilityAuditService;
import robot.agent.service.CapabilityService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/capabilities")
public class CapabilityController {

    private final CapabilityService capabilityService;
    private final CapabilityAuditService capabilityAuditService;

    public CapabilityController(
            CapabilityService capabilityService,
            CapabilityAuditService capabilityAuditService
    ) {
        this.capabilityService = capabilityService;
        this.capabilityAuditService = capabilityAuditService;
    }

    @GetMapping("/groups")
    public ResponseEntity<List<Map<String, Object>>> getGroups() {
        return ResponseEntity.ok(capabilityService.getCapabilityGroups());
    }

    @PostMapping("/groups")
    public ResponseEntity<Map<String, Object>> createGroup(
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return ResponseEntity.ok(capabilityService.saveCapabilityGroup(payload, null));
    }

    @PutMapping("/groups/{groupId}")
    public ResponseEntity<Map<String, Object>> updateGroup(
            @PathVariable Long groupId,
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return ResponseEntity.ok(capabilityService.saveCapabilityGroup(payload, groupId));
    }

    @DeleteMapping("/groups/{groupId}")
    public ResponseEntity<Void> deleteGroup(@PathVariable Long groupId) {
        capabilityService.deleteCapabilityGroup(groupId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/groups/{groupId}/items")
    public ResponseEntity<List<Map<String, Object>>> getItems(@PathVariable Long groupId) {
        return ResponseEntity.ok(capabilityService.getCapabilitiesByGroup(groupId));
    }

    @GetMapping("/groups/{groupId}/items/{capabilityCode}/versions")
    public ResponseEntity<List<Map<String, Object>>> getVersions(
            @PathVariable Long groupId,
            @PathVariable String capabilityCode
    ) {
        return ResponseEntity.ok(capabilityService.getCapabilityVersions(groupId, capabilityCode));
    }

    @PostMapping("/groups/{groupId}/items")
    public ResponseEntity<Map<String, Object>> createDraft(
            @PathVariable Long groupId,
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return ResponseEntity.ok(capabilityService.saveCapabilityDraft(groupId, null, payload));
    }

    @PutMapping("/groups/{groupId}/items/{capabilityCode}/draft")
    public ResponseEntity<Map<String, Object>> updateDraft(
            @PathVariable Long groupId,
            @PathVariable String capabilityCode,
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return ResponseEntity.ok(capabilityService.saveCapabilityDraft(groupId, capabilityCode, payload));
    }

    @PostMapping("/groups/{groupId}/items/{capabilityCode}/publish")
    public ResponseEntity<Map<String, Object>> publishCapability(
            @PathVariable Long groupId,
            @PathVariable String capabilityCode,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return ResponseEntity.ok(capabilityService.publishCapability(groupId, capabilityCode));
    }

    @DeleteMapping("/groups/{groupId}/items/{capabilityCode}")
    public ResponseEntity<Void> deleteCapability(
            @PathVariable Long groupId,
            @PathVariable String capabilityCode
    ) {
        capabilityService.deleteCapability(groupId, capabilityCode);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/groups/{groupId}/snapshots")
    public ResponseEntity<List<Map<String, Object>>> getSnapshots(@PathVariable Long groupId) {
        return ResponseEntity.ok(capabilityService.getCapabilityGroupSnapshots(groupId));
    }

    @PostMapping("/groups/{groupId}/snapshots/publish")
    public ResponseEntity<Map<String, Object>> publishSnapshot(
            @PathVariable Long groupId,
            @RequestBody(required = false) Map<String, Object> payload,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return ResponseEntity.ok(capabilityService.publishCapabilityGroupSnapshot(groupId, payload == null ? Map.of() : payload));
    }

    @GetMapping("/groups/{groupId}/auth-configs")
    public ResponseEntity<List<Map<String, Object>>> getAuthConfigs(@PathVariable Long groupId) {
        return ResponseEntity.ok(capabilityService.getCapabilityAuthConfigs(groupId));
    }

    @GetMapping("/groups/{groupId}/test-records")
    public ResponseEntity<List<Map<String, Object>>> getTestRecords(@PathVariable Long groupId) {
        return ResponseEntity.ok(capabilityService.getCapabilityTestRecords(groupId));
    }

    @GetMapping("/groups/{groupId}/audit-records")
    public ResponseEntity<List<Map<String, Object>>> getAuditRecords(@PathVariable Long groupId) {
        return ResponseEntity.ok(capabilityAuditService.getCapabilityAuditRecords(capabilityService.resolveGroupCode(groupId)));
    }

    @PostMapping("/groups/{groupId}/auth-configs")
    public ResponseEntity<Map<String, Object>> createAuthConfig(
            @PathVariable Long groupId,
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return ResponseEntity.ok(capabilityService.saveCapabilityAuthConfig(groupId, null, payload));
    }

    @PutMapping("/groups/{groupId}/auth-configs/{authConfigId}")
    public ResponseEntity<Map<String, Object>> updateAuthConfig(
            @PathVariable Long groupId,
            @PathVariable Long authConfigId,
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return ResponseEntity.ok(capabilityService.saveCapabilityAuthConfig(groupId, authConfigId, payload));
    }

    @PostMapping("/groups/{groupId}/validate-draft")
    public ResponseEntity<Map<String, Object>> validateDraft(
            @PathVariable Long groupId,
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return ResponseEntity.ok(capabilityService.validateCapabilityDraft(groupId, payload));
    }

    @PostMapping("/groups/{groupId}/items/{capabilityCode}/test")
    public ResponseEntity<Map<String, Object>> testCapability(
            @PathVariable Long groupId,
            @PathVariable String capabilityCode,
            @RequestBody(required = false) Map<String, Object> payload,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return ResponseEntity.ok(capabilityService.testCapability(groupId, capabilityCode, payload == null ? Map.of() : payload));
    }
}
