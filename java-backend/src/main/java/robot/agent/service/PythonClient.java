package robot.agent.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(PythonClient.class);
    private final WebClient webClient;

    public PythonClient(@Value("${python.base-url}") String baseUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    public Flux<ServerSentEvent<String>> execute(ExecuteRequest request) {
        log.info(
                "python.execute.request sessionId={} executionId={} workflowCode={} workflowVersion={} providerCount={} profileCount={}",
                request.getSessionId(),
                request.getExecutionId(),
                request.getWorkflowCode(),
                request.getWorkflowVersion(),
                request.getProviderConfigs() == null ? 0 : request.getProviderConfigs().size(),
                request.getModelProfiles() == null ? 0 : request.getModelProfiles().size()
        );
        return webClient.post()
                .uri("/api/execute")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .doOnError(error -> log.error("python.execute.failed executionId={} message={}", request.getExecutionId(), error.getMessage(), error));
    }

    public Mono<Void> submitForm(String executionId, FormSubmitRequest request) {
        log.info("python.form.submit executionId={} submitId={}", executionId, request == null ? null : request.getSubmitId());
        return webClient.post()
                .uri("/api/executions/{executionId}/form-submit", executionId)
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .toBodilessEntity()
                .then()
                .doOnError(error -> log.error("python.form.submit.failed executionId={} message={}", executionId, error.getMessage(), error));
    }

    public Mono<Map<String, Object>> suspendExecution(String executionId, String reason) {
        log.info("python.execution.suspend executionId={} reason={}", executionId, reason);
        return webClient.post()
                .uri("/api/executions/{executionId}/suspend", executionId)
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("reason", reason))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .doOnError(error -> log.error("python.execution.suspend.failed executionId={} message={}", executionId, error.getMessage(), error));
    }

    public Mono<Map<String, Object>> resumeExecution(String executionId) {
        log.info("python.execution.resume executionId={}", executionId);
        return webClient.post()
                .uri("/api/executions/{executionId}/resume", executionId)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .doOnError(error -> log.error("python.execution.resume.failed executionId={} message={}", executionId, error.getMessage(), error));
    }

    public Mono<Map<String, Object>> recommendSubflows(String workflowCode, String message) {
        log.info("python.subflow.recommend workflowCode={} messagePreview={}", workflowCode, preview(message));
        return webClient.post()
                .uri("/api/phase4/subflow-recommendations")
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "workflow_code", workflowCode,
                        "message", message == null ? "" : message
                ))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .doOnError(error -> log.error("python.subflow.recommend.failed workflowCode={} message={}", workflowCode, error.getMessage(), error));
    }

    public Mono<Map<String, Object>> evaluateRag(List<Map<String, Object>> dataset) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("dataset", dataset);
        log.info("python.rag.evaluate datasetSize={}", dataset == null ? 0 : dataset.size());
        return webClient.post()
                .uri("/api/phase4/evaluations/rag")
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .doOnError(error -> log.error("python.rag.evaluate.failed message={}", error.getMessage(), error));
    }

    public Mono<Map<String, Object>> classifyIntent(Map<String, Object> request) {
        log.info(
                "python.intent.classify messagePreview={} intentProfileCode={} candidateCount={}",
                preview(request == null ? null : String.valueOf(request.get("message"))),
                request == null ? null : request.get("intent_profile_code"),
                request != null && request.get("candidate_workflows") instanceof java.util.List<?> list ? list.size() : 0
        );
        return webClient.post()
                .uri("/api/phase5/intents/classify")
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .doOnError(error -> log.error("python.intent.classify.failed message={}", error.getMessage(), error));
    }

    private String preview(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80) + "...";
    }
}
