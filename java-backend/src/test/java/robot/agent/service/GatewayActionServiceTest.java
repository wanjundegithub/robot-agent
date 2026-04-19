package robot.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import robot.agent.dto.request.CreateWorkflowVersionRequest;
import robot.agent.dto.request.FormSubmitRequest;
import robot.agent.dto.request.SendMessageRequest;
import robot.agent.dto.response.FormSubmitResponse;
import robot.agent.dto.response.ResumeExecutionResponse;
import robot.agent.dto.response.SendMessageResponse;
import robot.agent.dto.response.WorkflowVersionResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayActionServiceTest {

    @Test
    void handleSendMessageProducesAck() {
        CapturingGatewayHub hub = new CapturingGatewayHub(new ObjectMapper());
        GatewayActionService service = new GatewayActionService(
                new ObjectMapper(),
                hub,
                new StubExecutionGateway()
        );
        NettyGatewayHub.GatewayConnection connection = hub.register("sess_1", null);

        service.handle("""
                {"type":"action","request_id":"req_1","action":"send_message","session_id":"sess_1","payload":{"content":"测试消息","message_id":"msg_1","user_id":"demo-user"}}
                """, connection).block();

        assertThat(hub.payloads).isNotEmpty();
        assertThat(hub.payloads.get(0).get("type")).isEqualTo("ack");
        assertThat(hub.payloads.get(0).get("action")).isEqualTo("send_message");
    }

    private static class CapturingGatewayHub extends NettyGatewayHub {
        private final List<Map<String, Object>> payloads = new ArrayList<>();

        CapturingGatewayHub(ObjectMapper objectMapper) {
            super(objectMapper);
        }

        @Override
        public void send(String connectionId, Map<String, Object> payload) {
            payloads.add(payload);
        }
    }

    private static class StubExecutionGateway implements ExecutionCommandGateway {
        @Override
        public SendMessageResponse startExecution(String sessionId, SendMessageRequest request) {
            SendMessageResponse response = new SendMessageResponse();
            response.setSessionId(sessionId);
            response.setExecutionId("exec_1");
            response.setWorkflowCode("flight_booking");
            response.setWorkflowVersion("2.0.0");
            response.setStatus("running");
            return response;
        }

        @Override
        public FormSubmitResponse submitForm(String executionId, FormSubmitRequest request) {
            FormSubmitResponse response = new FormSubmitResponse();
            response.setExecutionId(executionId);
            response.setStatus("running");
            return response;
        }

        @Override
        public ResumeExecutionResponse resumeExecution(String executionId) {
            ResumeExecutionResponse response = new ResumeExecutionResponse();
            response.setExecutionId(executionId);
            response.setStatus("running");
            return response;
        }
    }
}
