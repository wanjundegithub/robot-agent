package robot.agent.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import robot.agent.model.LlmModelRecord;
import robot.agent.model.LlmProviderConfig;
import robot.agent.repository.LlmModelRecordRepository;
import robot.agent.repository.LlmProviderConfigRepository;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UnifiedModelServiceTest {

    private final LlmModelRecordRepository modelRecordRepository = mock(LlmModelRecordRepository.class);
    private final LlmProviderConfigRepository providerRepository = mock(LlmProviderConfigRepository.class);
    private final UnifiedModelService unifiedModelService = new UnifiedModelService(modelRecordRepository, providerRepository);

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void invokeChatUsesModelRecordAndExtractsOpenAiText() {
        stubProviderResponse("""
                {"choices":[{"message":{"content":"connectivity ok"}}],"usage":{"prompt_tokens":12,"completion_tokens":8,"total_tokens":20}}
                """);
        when(modelRecordRepository.findByModelCode("general-chat"))
                .thenReturn(Optional.of(modelRecord("general-chat", "General Chat")));
        when(providerRepository.findByProviderCode("provider-a"))
                .thenReturn(Optional.of(provider("provider-a", "openai")));

        UnifiedModelResult result = unifiedModelService.invokeChat(new UnifiedModelRequest(
                "general-chat",
                List.of(Map.of("role", "user", "content", "ping")),
                "system",
                Map.of(),
                null,
                false
        ));

        assertThat(result.text()).isEqualTo("connectivity ok");
        assertThat(result.usage().get("total_tokens")).isEqualTo(20);
    }

    @Test
    void invokeChatMapsMissingModelToStableErrorCode() {
        when(modelRecordRepository.findByModelCode("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> unifiedModelService.invokeChat(new UnifiedModelRequest("missing", List.of(), "", Map.of(), null, false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("MODEL_NOT_FOUND");
    }

    private void stubProviderResponse(String jsonBody) {
        try {
            server = HttpServer.create(new InetSocketAddress(0), 0);
        } catch (IOException exception) {
            throw new RuntimeException(exception);
        }
        server.createContext("/chat/completions", exchange -> {
            byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(payload);
            }
        });
        server.start();
    }

    private LlmModelRecord modelRecord(String modelCode, String modelName) {
        LlmModelRecord record = new LlmModelRecord();
        record.setModelCode(modelCode);
        record.setModelName(modelName);
        record.setProviderCode("provider-a");
        record.setProviderModelCode(modelCode);
        record.setEnabled(true);
        return record;
    }

    private LlmProviderConfig provider(String providerCode, String providerType) {
        LlmProviderConfig provider = new LlmProviderConfig();
        provider.setProviderCode(providerCode);
        provider.setProviderType(providerType);
        provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        provider.setApiKeySecretRef("test-api-key");
        provider.setDefaultModelCode("gpt-4o-mini");
        provider.setEnabled(true);
        return provider;
    }
}
