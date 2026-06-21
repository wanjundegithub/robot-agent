package robot.agent.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import robot.agent.dto.request.TestModelRecordRequest;
import robot.agent.dto.request.UpsertModelRecordRequest;
import robot.agent.service.ModelConfigService;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelConfigControllerTest {

    @Test
    void modelRecordResourceUsesModelCodeForReadUpdateAndDelete() {
        ModelConfigService service = mock(ModelConfigService.class);
        ModelConfigController controller = new ModelConfigController(service);
        UpsertModelRecordRequest request = new UpsertModelRecordRequest();
        request.setModelCode("general-chat-v1");
        when(service.getModelRecord("general-chat-v1")).thenReturn(Map.of("model_code", "general-chat-v1"));
        when(service.updateModelRecord("demo-admin", "general-chat-v1", request))
                .thenReturn(Map.of("model_code", "general-chat-v1"));

        ResponseEntity<Map<String, Object>> read = controller.getModel("general-chat-v1");
        ResponseEntity<Map<String, Object>> updated = controller.updateModelRecord("demo-admin", "general-chat-v1", request);
        ResponseEntity<Void> deleted = controller.deleteModelRecord("demo-admin", "general-chat-v1");

        assertThat(read.getBody()).containsEntry("model_code", "general-chat-v1");
        assertThat(updated.getBody()).containsEntry("model_code", "general-chat-v1");
        assertThat(deleted.getStatusCode().value()).isEqualTo(204);
        verify(service).getModelRecord("general-chat-v1");
        verify(service).updateModelRecord("demo-admin", "general-chat-v1", request);
        verify(service).deleteModelRecord("demo-admin", "general-chat-v1");
    }

    @Test
    void modelRecordTestEndpointUsesModelCode() {
        ModelConfigService service = mock(ModelConfigService.class);
        ModelConfigController controller = new ModelConfigController(service);
        TestModelRecordRequest request = new TestModelRecordRequest();
        request.setMessage("ping");
        when(service.testModelRecordChat("demo-admin", "general-chat-v1", request))
                .thenReturn(Map.of("ok", true, "model_code", "general-chat-v1"));

        ResponseEntity<Map<String, Object>> result = controller.testModelRecord("demo-admin", "general-chat-v1", request);

        assertThat(result.getBody()).containsEntry("model_code", "general-chat-v1");
        verify(service).testModelRecordChat("demo-admin", "general-chat-v1", request);
    }
}
