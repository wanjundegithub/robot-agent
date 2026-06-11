package robot.agent.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;
import robot.agent.service.PythonClient;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FunctionFragmentControllerTest {

    @Test
    void validateProxiesRequestToPythonService() {
        PythonClient pythonClient = mock(PythonClient.class);
        FunctionFragmentController controller = new FunctionFragmentController(pythonClient);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("language", "python");
        request.put("code", "ctx['local']['result'] = 'ok'");
        Map<String, Object> response = Map.of("valid", true, "error_message", "");
        when(pythonClient.validateFunctionFragment(request)).thenReturn(Mono.just(response));

        ResponseEntity<Map<String, Object>> result = controller.validate(request);

        assertThat(result.getBody()).isEqualTo(response);
        verify(pythonClient).validateFunctionFragment(request);
    }

    @Test
    void testRunProxiesRequestToPythonService() {
        PythonClient pythonClient = mock(PythonClient.class);
        FunctionFragmentController controller = new FunctionFragmentController(pythonClient);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("code", "print('ok')");
        request.put("variables", Map.of("global", Map.of(), "local", Map.of()));
        Map<String, Object> response = Map.of("success", true, "stdout", "ok\n");
        when(pythonClient.testRunFunctionFragment(request)).thenReturn(Mono.just(response));

        ResponseEntity<Map<String, Object>> result = controller.testRun(request);

        assertThat(result.getBody()).isEqualTo(response);
        verify(pythonClient).testRunFunctionFragment(request);
    }
}
