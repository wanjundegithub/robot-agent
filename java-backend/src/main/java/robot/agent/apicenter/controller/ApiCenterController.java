package robot.agent.apicenter.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import robot.agent.apicenter.service.ApiCenterService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/api-center")
public class ApiCenterController {

    private final ApiCenterService apiCenterService;

    public ApiCenterController(ApiCenterService apiCenterService) {
        this.apiCenterService = apiCenterService;
    }

    @GetMapping("/groups")
    public ResponseEntity<List<Map<String, Object>>> getGroups() {
        return ResponseEntity.ok(apiCenterService.getGroups());
    }

    @PostMapping("/groups")
    public ResponseEntity<Map<String, Object>> createGroup(@RequestBody Map<String, Object> payload) {
        return ResponseEntity.ok(apiCenterService.saveGroup(payload, null));
    }

    @PutMapping("/groups/{groupId}")
    public ResponseEntity<Map<String, Object>> updateGroup(@PathVariable Long groupId, @RequestBody Map<String, Object> payload) {
        return ResponseEntity.ok(apiCenterService.saveGroup(payload, groupId));
    }

    @DeleteMapping("/groups/{groupId}")
    public ResponseEntity<Void> deleteGroup(@PathVariable Long groupId) {
        apiCenterService.deleteGroup(groupId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/groups/{groupId}/auth-config")
    public ResponseEntity<Map<String, Object>> getGroupAuthConfig(@PathVariable Long groupId) {
        return ResponseEntity.ok(apiCenterService.getGroupAuthConfig(groupId));
    }

    @PutMapping("/groups/{groupId}/auth-config")
    public ResponseEntity<Map<String, Object>> saveGroupAuthConfig(@PathVariable Long groupId, @RequestBody Map<String, Object> payload) {
        return ResponseEntity.ok(apiCenterService.saveGroupAuthConfig(groupId, payload));
    }

    @GetMapping("/groups/{groupId}/items")
    public ResponseEntity<List<Map<String, Object>>> getItems(@PathVariable Long groupId) {
        return ResponseEntity.ok(apiCenterService.getItems(groupId));
    }

    @GetMapping("/groups/{groupId}/items/{apiId}")
    public ResponseEntity<Map<String, Object>> getItem(@PathVariable Long groupId, @PathVariable Long apiId) {
        return ResponseEntity.ok(apiCenterService.getItem(groupId, apiId));
    }

    @PostMapping("/groups/{groupId}/items")
    public ResponseEntity<Map<String, Object>> createItem(@PathVariable Long groupId, @RequestBody Map<String, Object> payload) {
        return ResponseEntity.ok(apiCenterService.saveItem(groupId, null, payload));
    }

    @PutMapping("/groups/{groupId}/items/{apiId}")
    public ResponseEntity<Map<String, Object>> updateItem(@PathVariable Long groupId, @PathVariable Long apiId, @RequestBody Map<String, Object> payload) {
        return ResponseEntity.ok(apiCenterService.saveItem(groupId, apiId, payload));
    }

    @DeleteMapping("/groups/{groupId}/items/{apiId}")
    public ResponseEntity<Void> deleteItem(@PathVariable Long groupId, @PathVariable Long apiId) {
        apiCenterService.deleteItem(groupId, apiId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/groups/{groupId}/items/{apiId}/auth-config")
    public ResponseEntity<Map<String, Object>> getItemAuthConfig(@PathVariable Long groupId, @PathVariable Long apiId) {
        return ResponseEntity.ok(apiCenterService.getItemAuthConfig(groupId, apiId));
    }

    @PutMapping("/groups/{groupId}/items/{apiId}/auth-config")
    public ResponseEntity<Map<String, Object>> saveItemAuthConfig(
            @PathVariable Long groupId,
            @PathVariable Long apiId,
            @RequestBody Map<String, Object> payload
    ) {
        return ResponseEntity.ok(apiCenterService.saveItemAuthConfig(groupId, apiId, payload));
    }

    @PostMapping("/groups/{groupId}/validate")
    public ResponseEntity<Map<String, Object>> validate(@PathVariable Long groupId, @RequestBody Map<String, Object> payload) {
        return ResponseEntity.ok(apiCenterService.validateDraft(groupId, payload));
    }

    @PostMapping("/groups/{groupId}/test")
    public ResponseEntity<Map<String, Object>> test(
            @PathVariable Long groupId,
            @RequestBody Map<String, Object> payload
    ) {
        return ResponseEntity.ok(apiCenterService.testDraft(groupId, payload));
    }
}
