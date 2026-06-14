package robot.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class UnifiedModelServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void invokeDirectChatParsesOpenAiCompatibleStreamAndPreservesFullEndpoint() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    data: {"choices":[{"delta":{"reasoning_content":"比较小数位"}}]}

                    data: {"choices":[{"delta":{"content":"9.9更大"}}]}

                    data: [DONE]

                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            UnifiedModelService service = new UnifiedModelService(null, null, objectMapper);
            String fullEndpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";

            UnifiedModelResult result = service.invokeDirectChat(
                    "custom",
                    fullEndpoint,
                    "test-secret",
                    "Qwen/Qwen3-8B",
                    List.of(Map.of("role", "user", "content", "9.9和9.11谁大")),
                    Map.of("stream", true, "enable_thinking", true)
            );

            Map<String, Object> body = objectMapper.readValue(requestBody.get(), new TypeReference<>() {});
            assertThat(requestPath.get()).isEqualTo("/v1/chat/completions");
            assertThat(authorization.get()).isEqualTo("Bearer test-secret");
            assertThat(body).containsEntry("model", "Qwen/Qwen3-8B");
            assertThat(body).containsEntry("stream", true);
            assertThat(body).containsEntry("enable_thinking", true);
            assertThat(result.text()).isEqualTo("9.9更大");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void invokeDirectEmbeddingParsesOpenAiCompatiblePayloadAndPreservesFullEndpoint() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {
                      "data": [
                        {
                          "embedding": [0.0172119140625, -0.00928497314453125, 0.005107879638671875],
                          "index": 0,
                          "object": "embedding"
                        }
                      ],
                      "model": "Qwen/Qwen3-Embedding-8B",
                      "object": "list",
                      "usage": {
                        "prompt_tokens": 2,
                        "completion_tokens": 0,
                        "total_tokens": 2
                      },
                      "id": "embd-test"
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            UnifiedModelService service = new UnifiedModelService(null, null, objectMapper);
            String fullEndpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/embeddings";

            UnifiedModelResult result = service.invokeDirectEmbedding(
                    "custom",
                    fullEndpoint,
                    "test-secret",
                    "Qwen/Qwen3-Embedding-8B",
                    Map.of("input", "hello", "encoding_format", "float")
            );

            Map<String, Object> body = objectMapper.readValue(requestBody.get(), new TypeReference<>() {});
            assertThat(requestPath.get()).isEqualTo("/v1/embeddings");
            assertThat(authorization.get()).isEqualTo("Bearer test-secret");
            assertThat(body).containsEntry("model", "Qwen/Qwen3-Embedding-8B");
            assertThat(body).containsEntry("input", "hello");
            assertThat(body).containsEntry("encoding_format", "float");
            assertThat(result.text()).isEqualTo("Embedding call succeeded: 1 vector(s), dimension 3");
            assertThat(result.usage()).containsEntry("total_tokens", 2);
        } finally {
            server.stop(0);
        }
    }
}
