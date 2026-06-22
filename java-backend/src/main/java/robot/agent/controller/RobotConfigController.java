package robot.agent.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import robot.agent.dto.request.UpdateRobotBindingsRequest;
import robot.agent.dto.request.UpsertRobotConfigRequest;
import robot.agent.dto.response.RobotBindingResponse;
import robot.agent.dto.response.RobotConfigResponse;
import robot.agent.service.robot.RobotConfigService;

import java.util.List;

@RestController
@RequestMapping("/api/robots")
public class RobotConfigController {
    private final RobotConfigService service;

    public RobotConfigController(RobotConfigService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<RobotConfigResponse>> listRobots() {
        return ResponseEntity.ok(service.listRobots());
    }

    @PostMapping
    public ResponseEntity<RobotConfigResponse> upsertRobot(@RequestBody UpsertRobotConfigRequest request) {
        return ResponseEntity.ok(service.upsertRobot(request));
    }

    @GetMapping("/{robotCode}")
    public ResponseEntity<RobotConfigResponse> getRobot(@PathVariable String robotCode) {
        return ResponseEntity.ok(service.getRobot(robotCode));
    }

    @PutMapping("/{robotCode}")
    public ResponseEntity<RobotConfigResponse> updateRobot(
            @PathVariable String robotCode,
            @RequestBody UpsertRobotConfigRequest request
    ) {
        request.setRobotCode(robotCode);
        return ResponseEntity.ok(service.upsertRobot(request));
    }

    @PostMapping("/{robotCode}/publish")
    public ResponseEntity<RobotConfigResponse> publishRobot(@PathVariable String robotCode) {
        return ResponseEntity.ok(service.publishRobot(robotCode));
    }

    @GetMapping("/{robotCode}/bindings")
    public ResponseEntity<List<RobotBindingResponse>> getBindings(@PathVariable String robotCode) {
        return ResponseEntity.ok(service.getBindings(robotCode));
    }

    @PutMapping("/{robotCode}/bindings")
    public ResponseEntity<List<RobotBindingResponse>> updateBindings(
            @PathVariable String robotCode,
            @RequestBody UpdateRobotBindingsRequest request
    ) {
        return ResponseEntity.ok(service.replaceBindings(robotCode, request));
    }
}
