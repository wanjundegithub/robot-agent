package robot.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UnifiedModelServiceTest {

    private static final LocalDateTime FIXED_UPDATED_AT = LocalDateTime.of(2026, 4, 26, 10, 30, 0);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
        Object totalTokens = result.usage().get("total_tokens");
        assertThat(totalTokens).isInstanceOf(Number.class);
        assertThat(((Number) totalTokens).intValue()).isEqualTo(20);
    }

    @Test
    void invokeChatMapsMissingModelToStableErrorCode() {
        when(modelRecordRepository.findByModelCode("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> unifiedModelService.invokeChat(new UnifiedModelRequest(
                "missing",
                List.of(Map.of("role", "user", "content", "ping")),
                "system",
                Map.of(),
                null,
                false
        )))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getReason())
                        .contains("MODEL_NOT_FOUND"));
    }

    private void stubProviderResponse(String jsonBody) {
        try {
            server = HttpServer.create(new InetSocketAddress(0), 0);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to start local stub provider server for UnifiedModelServiceTest", exception);
        }
        server.createContext("/chat/completions", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                throw new AssertionError("Expected POST request but got: " + exchange.getRequestMethod());
            }
            if (!"/chat/completions".equals(exchange.getRequestURI().getPath())) {
                throw new AssertionError("Unexpected request path: " + exchange.getRequestURI().getPath());
            }
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<?, ?> requestJson = OBJECT_MAPPER.readValue(requestBody, Map.class);
            Object model = requestJson.get("model");
            if (model == null || String.valueOf(model).isBlank()) {
                throw new AssertionError("Expected non-empty model field in request payload: " + requestBody);
            }
            if (!(requestJson.get("messages") instanceof List<?> messages) || messages.isEmpty()) {
                throw new AssertionError("Expected non-empty messages field in request payload: " + requestBody);
            }

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
        record.setUpdatedAt(FIXED_UPDATED_AT);
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
