package robot.agent.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import robot.agent.dto.request.ExecuteRequest;
import robot.agent.dto.request.FormSubmitRequest;

import java.util.List;
import java.util.Map;

@Service
public class PythonClient {
    private final WebClient webClient;

    public PythonClient(@Value("${python.base-url}") String baseUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    public Flux<ServerSentEvent<String>> execute(ExecuteRequest request) {
        return webClient.post()
                .uri("/api/execute")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {});
    }

    public Mono<Void> submitForm(String executionId, FormSubmitRequest request) {
        return webClient.post()
                .uri("/api/executions/{executionId}/form-submit", executionId)
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .toBodilessEntity()
                .then();
    }

    public Mono<Map<String, Object>> suspendExecution(String executionId, String reason) {
        return webClient.post()
                .uri("/api/executions/{executionId}/suspend", executionId)
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("reason", reason))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> resumeExecution(String executionId) {
        return webClient.post()
                .uri("/api/executions/{executionId}/resume", executionId)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> recommendSubflows(String workflowCode, String message) {
        return webClient.post()
                .uri("/api/phase4/subflow-recommendations")
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "workflow_code", workflowCode,
                        "message", message == null ? "" : message
                ))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> evaluateRag(List<Map<String, Object>> dataset) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("dataset", dataset);
        return webClient.post()
                .uri("/api/phase4/evaluations/rag")
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> classifyIntent(Map<String, Object> request) {
        return webClient.post()
                .uri("/api/phase5/intents/classify")
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }
}
