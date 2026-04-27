package robot.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
    private final AtomicReference<String> handlerFailure = new AtomicReference<>();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void springContextCanInstantiateUnifiedModelServiceBean() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(LlmModelRecordRepository.class, () -> modelRecordRepository);
            context.registerBean(LlmProviderConfigRepository.class, () -> providerRepository);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(UnifiedModelService.class);

            assertThatCode(context::refresh).doesNotThrowAnyException();
            assertThat(context.getBean(UnifiedModelService.class)).isNotNull();
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
        assertThat(handlerFailure.get()).as("provider request assertions").isNull();

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
                .satisfies(error -> {
                    ResponseStatusException exception = (ResponseStatusException) error;
                    assertThat(exception.getStatusCode().is4xxClientError()).isTrue();
                    assertThat(exception.getReason()).contains("MODEL_NOT_FOUND");
                });
    }

    private void stubProviderResponse(String jsonBody) {
        handlerFailure.set(null);
        try {
            server = HttpServer.create(new InetSocketAddress(0), 0);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to start local stub provider server for UnifiedModelServiceTest", exception);
        }
        server.createContext("/chat/completions", exchange -> {
            try {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    handlerFailure.compareAndSet(null, "Expected POST request but got: " + exchange.getRequestMethod());
                }
                if (!"/chat/completions".equals(exchange.getRequestURI().getPath())) {
                    handlerFailure.compareAndSet(null, "Unexpected request path: " + exchange.getRequestURI().getPath());
                }
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                if (authorization == null || authorization.isBlank() || !authorization.startsWith("Bearer ")) {
                    handlerFailure.compareAndSet(null, "Expected Bearer Authorization header but got: " + authorization);
                }
                String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<?, ?> requestJson = OBJECT_MAPPER.readValue(requestBody, Map.class);
                Object model = requestJson.get("model");
                if (model == null || String.valueOf(model).isBlank()) {
                    handlerFailure.compareAndSet(null, "Expected non-empty model field in request payload: " + requestBody);
                }
                if (!(requestJson.get("messages") instanceof List<?> messages) || messages.isEmpty()) {
                    handlerFailure.compareAndSet(null, "Expected non-empty messages field in request payload: " + requestBody);
                }
            } catch (Exception exception) {
                handlerFailure.compareAndSet(null, "Handler validation failed: " + exception.getMessage());
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
        provider.setEnabled(true);
        return provider;
    }
}
