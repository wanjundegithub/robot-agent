package robot.agent.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PythonClientFunctionFragmentTest {

    private HttpServer server;
    private PythonClient client;
    private String lastPath;
    private String lastMethod;
    private String lastBody;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/function-fragments/validate", exchange -> respond(exchange, "{\"valid\":true}"));
        server.createContext("/api/function-fragments/test-run", exchange -> respond(exchange, "{\"success\":true,\"stdout\":\"ok\\n\"}"));
        server.start();
        client = new PythonClient("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void validateFunctionFragmentPostsToPythonValidateEndpoint() {
        Map<String, Object> response = client.validateFunctionFragment(Map.of("code", "print('ok')")).block();

        assertThat(response).containsEntry("valid", true);
        assertThat(lastMethod).isEqualTo("POST");
        assertThat(lastPath).isEqualTo("/api/function-fragments/validate");
        assertThat(lastBody).contains("\"code\":\"print('ok')\"");
    }

    @Test
    void testRunFunctionFragmentPostsToPythonTestRunEndpoint() {
        Map<String, Object> response = client.testRunFunctionFragment(Map.of("code", "print('ok')")).block();

        assertThat(response).containsEntry("success", true);
        assertThat(lastMethod).isEqualTo("POST");
        assertThat(lastPath).isEqualTo("/api/function-fragments/test-run");
        assertThat(lastBody).contains("\"code\":\"print('ok')\"");
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        lastPath = exchange.getRequestURI().getPath();
        lastMethod = exchange.getRequestMethod();
        lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
