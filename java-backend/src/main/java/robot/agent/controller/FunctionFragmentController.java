package robot.agent.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import robot.agent.service.PythonClient;

import java.util.Map;

@RestController
@RequestMapping("/api/workflows/function-fragments")
public class FunctionFragmentController {

    private final PythonClient pythonClient;

    public FunctionFragmentController(PythonClient pythonClient) {
        this.pythonClient = pythonClient;
    }

    @PostMapping("/validate")
    public ResponseEntity<Map<String, Object>> validate(@RequestBody Map<String, Object> request) {
        return ResponseEntity.ok(pythonClient.validateFunctionFragment(request).block());
    }

    @PostMapping("/test-run")
    public ResponseEntity<Map<String, Object>> testRun(@RequestBody Map<String, Object> request) {
        return ResponseEntity.ok(pythonClient.testRunFunctionFragment(request).block());
    }
}
