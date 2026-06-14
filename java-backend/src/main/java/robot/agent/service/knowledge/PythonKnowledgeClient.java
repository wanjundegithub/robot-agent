package robot.agent.service.knowledge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Service
public class PythonKnowledgeClient {
    private final WebClient webClient;

    public PythonKnowledgeClient(@Value("${python.base-url}") String pythonBaseUrl) {
        this.webClient = WebClient.builder().baseUrl(pythonBaseUrl).build();
    }

    public Map<String, Object> ingest(Map<String, Object> request) {
        return webClient.post()
                .uri("/api/knowledge/ingest")
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
    }

    public Map<String, Object> search(Map<String, Object> request) {
        return webClient.post()
                .uri("/api/knowledge/search")
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
    }
}
