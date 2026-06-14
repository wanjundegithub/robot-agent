package robot.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import robot.agent.model.LlmModelRecord;
import robot.agent.model.LlmProviderConfig;
import robot.agent.repository.LlmModelRecordRepository;
import robot.agent.repository.LlmProviderConfigRepository;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Service
@Transactional(readOnly = true)
public class UnifiedModelService {
    private static final Logger log = LoggerFactory.getLogger(UnifiedModelService.class);

    private final LlmModelRecordRepository modelRecordRepository;
    private final LlmProviderConfigRepository providerRepository;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    @Autowired
    public UnifiedModelService(
            LlmModelRecordRepository modelRecordRepository,
            LlmProviderConfigRepository providerRepository,
            ObjectMapper objectMapper
    ) {
        this.modelRecordRepository = modelRecordRepository;
        this.providerRepository = providerRepository;
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder().build();
    }

    public UnifiedModelService(
            LlmModelRecordRepository modelRecordRepository,
            LlmProviderConfigRepository providerRepository
    ) {
        this(modelRecordRepository, providerRepository, new ObjectMapper());
    }

    public UnifiedModelResult invokeChat(UnifiedModelRequest request) {
        long startedAt = System.currentTimeMillis();
        String modelCode = required(request.modelCode(), "model_code");
        LlmModelRecord modelRecord = modelRecordRepository.findByModelCode(modelCode)
                .orElseThrow(() -> stableError("MODEL_NOT_FOUND", "Model record not found: " + modelCode));
        if (!modelRecord.isEnabled()) {
            throw stableError("MODEL_DISABLED", "Model record is disabled: " + modelCode);
        }

        LlmProviderConfig provider = providerRepository.findByProviderCode(modelRecord.getProviderCode())
                .orElseThrow(() -> stableError("PROVIDER_NOT_FOUND", "Provider not found: " + modelRecord.getProviderCode()));
        if (!provider.isEnabled()) {
            throw stableError("PROVIDER_DISABLED", "Provider is disabled: " + provider.getProviderCode());
        }

        Map<String, Object> effectiveOptions = mergeOptions(parseJson(modelRecord.getDefaultOptionsJson()), request.options());
        Integer timeoutFromOptions = integerValue(effectiveOptions.get("timeout_sec"));
        int timeoutSec = request.timeoutSec() != null
                ? request.timeoutSec()
                : (timeoutFromOptions != null ? timeoutFromOptions : 30);
        String upstreamModelCode = firstNonBlank(modelRecord.getUpstreamModelCode(), modelRecord.getModelCode());
        String systemPrompt = firstNonBlank(request.systemPrompt(), modelRecord.getDefaultSystemPrompt(), "");
        List<Map<String, Object>> messages = normalizeMessages(request.messages());

        ProviderRequest providerRequest = buildChatRequest(
                provider,
                upstreamModelCode,
                systemPrompt,
                messages,
                effectiveOptions
        );
        log.info(
                "model.invoke.request mode=managed modelCode={} providerCode={} providerType={} upstreamModelCode={} timeoutSec={} messageCount={}",
                modelRecord.getModelCode(),
                provider.getProviderCode(),
                provider.getProviderType(),
                upstreamModelCode,
                timeoutSec,
                messages.size()
        );
        Map<String, Object> payload = postForMap(providerRequest, timeoutSec);
        String providerProtocol = resolveProviderProtocol(normalizeProviderType(provider.getProviderType()));
        String text = extractText(providerProtocol, payload);
        Map<String, Object> usage = extractUsage(providerProtocol, payload);

        log.info(
                "model.invoke.completed mode=managed modelCode={} providerCode={} durationMs={} usageKeys={}",
                modelRecord.getModelCode(),
                provider.getProviderCode(),
                System.currentTimeMillis() - startedAt,
                usage.keySet()
        );
        return new UnifiedModelResult(
                modelRecord.getModelCode(),
                provider.getProviderCode(),
                upstreamModelCode,
                text,
                usage,
                request.includeRawResponse() ? payload : Map.of()
        );
    }

    public UnifiedModelResult invokeDirectChat(
            String providerType,
            String baseUrl,
            String apiKey,
            String modelName,
            List<? extends Map<String, ?>> messages,
            Map<String, ?> options
    ) {
        long startedAt = System.currentTimeMillis();
        LlmProviderConfig provider = new LlmProviderConfig();
        provider.setProviderType(required(providerType, "provider"));
        provider.setBaseUrl(required(baseUrl, "base_url").replaceAll("/+$", ""));
        provider.setApiKeySecretRef(required(apiKey, "api_key"));
        provider.setEnabled(true);

        Map<String, Object> effectiveOptions = new LinkedHashMap<>();
        if (options != null) {
            effectiveOptions.putAll(options);
        }
        int timeoutSec = integerValue(effectiveOptions.get("timeout_sec")) == null
                ? 30
                : Math.max(5, integerValue(effectiveOptions.get("timeout_sec")));
        ProviderRequest providerRequest = buildChatRequest(
                provider,
                required(modelName, "model_name"),
                "",
                normalizeMessages(messages),
                effectiveOptions
        );
        log.info(
                "model.invoke.request mode=direct providerType={} modelName={} timeoutSec={} messageCount={}",
                providerType,
                modelName,
                timeoutSec,
                messages == null ? 0 : messages.size()
        );
        Map<String, Object> payload = postForMap(providerRequest, timeoutSec);
        String providerProtocol = resolveProviderProtocol(normalizeProviderType(provider.getProviderType()));
        String text = extractText(providerProtocol, payload);
        Map<String, Object> usage = extractUsage(providerProtocol, payload);
        log.info(
                "model.invoke.completed mode=direct providerType={} modelName={} durationMs={} usageKeys={}",
                providerType,
                modelName,
                System.currentTimeMillis() - startedAt,
                usage.keySet()
        );
        return new UnifiedModelResult(
                "draft-" + normalizeProviderType(providerType),
                "draft-provider",
                modelName,
                text,
                usage,
                payload
        );
    }

    public UnifiedModelResult invokeDirectEmbedding(
            String providerType,
            String baseUrl,
            String apiKey,
            String modelName,
            Map<String, ?> options
    ) {
        long startedAt = System.currentTimeMillis();
        LlmProviderConfig provider = new LlmProviderConfig();
        provider.setProviderType(required(providerType, "provider"));
        provider.setBaseUrl(required(baseUrl, "base_url").replaceAll("/+$", ""));
        provider.setApiKeySecretRef(required(apiKey, "api_key"));
        provider.setEnabled(true);

        Map<String, Object> effectiveOptions = new LinkedHashMap<>();
        if (options != null) {
            effectiveOptions.putAll(options);
        }
        int timeoutSec = integerValue(effectiveOptions.get("timeout_sec")) == null
                ? 30
                : Math.max(5, integerValue(effectiveOptions.get("timeout_sec")));
        ProviderRequest providerRequest = buildEmbeddingRequest(
                provider,
                required(modelName, "model_name"),
                effectiveOptions
        );
        log.info(
                "model.invoke.request mode=direct-embedding providerType={} modelName={} timeoutSec={} optionKeys={}",
                providerType,
                modelName,
                timeoutSec,
                effectiveOptions.keySet()
        );
        Map<String, Object> payload = postForMap(providerRequest, timeoutSec);
        List<List<Double>> vectors = extractEmbeddingVectors(payload);
        Map<String, Object> usage = extractUsage(resolveProviderProtocol(normalizeProviderType(provider.getProviderType())), payload);
        String text = summarizeEmbeddingResponse(vectors);
        log.info(
                "model.invoke.completed mode=direct-embedding providerType={} modelName={} durationMs={} vectorCount={} dimension={} usageKeys={}",
                providerType,
                modelName,
                System.currentTimeMillis() - startedAt,
                vectors.size(),
                vectors.isEmpty() ? 0 : vectors.get(0).size(),
                usage.keySet()
        );
        return new UnifiedModelResult(
                "draft-" + normalizeProviderType(providerType),
                "draft-provider",
                modelName,
                text,
                usage,
                payload
        );
    }

    public int validateProviderConnection(LlmProviderConfig provider, String modelCode, Map<String, Object> requestBodyOverride) {
        long startedAt = System.currentTimeMillis();
        String providerType = normalizeProviderType(required(provider.getProviderType(), "provider_type"));
        String providerProtocol = resolveProviderProtocol(providerType);
        ProviderRequest providerRequest = buildProbeRequest(provider, providerProtocol, required(modelCode, "model_code"), requestBodyOverride);
        log.info(
                "model.provider.validate.request providerCode={} providerType={} modelCode={} customBody={}",
                provider.getProviderCode(),
                providerType,
                modelCode,
                requestBodyOverride != null && !requestBodyOverride.isEmpty()
        );
        try {
            ResponseEntity<Void> response = webClient.post()
                    .uri(providerRequest.url())
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> providerRequest.headers().forEach(headers::set))
                    .bodyValue(providerRequest.body())
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(20));
            if (response == null || !response.getStatusCode().is2xxSuccessful()) {
                throw stableError("PROVIDER_REQUEST_FAILED", "Provider validation request failed");
            }
            log.info(
                    "model.provider.validate.completed providerCode={} providerType={} modelCode={} status={} durationMs={}",
                    provider.getProviderCode(),
                    providerType,
                    modelCode,
                    response.getStatusCode().value(),
                    System.currentTimeMillis() - startedAt
            );
            return response.getStatusCode().value();
        } catch (WebClientResponseException exception) {
            throw stableError("PROVIDER_REQUEST_FAILED", responseMessage(exception));
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw stableError("PROVIDER_REQUEST_FAILED", exception.getMessage());
        }
    }

    private ProviderRequest buildChatRequest(
            LlmProviderConfig provider,
            String modelCode,
            String systemPrompt,
            List<Map<String, Object>> messages,
            Map<String, Object> options
    ) {
        String providerProtocol = resolveProviderProtocol(normalizeProviderType(provider.getProviderType()));
        String baseUrl = normalizedBaseUrl(provider);
        Map<String, String> headers = buildHeaders(provider);
        double temperature = decimalValue(options.get("temperature"), 0.2d);
        double topP = decimalValue(options.get("top_p"), 1.0d);
        int maxTokens = integerValue(options.get("max_tokens")) == null ? 256 : Math.max(1, integerValue(options.get("max_tokens")));
        Map<String, Object> responseFormat = asMap(options.get("response_format"));
        String apiKey = firstNonBlank(resolveSecretOrEmpty(provider.getApiKeySecretRef()), "");

        return switch (providerProtocol) {
            case "openai", "openai_compatible", "deepseek", "qwen", "custom" -> {
                List<Map<String, Object>> providerMessages = new ArrayList<>();
                if (systemPrompt != null && !systemPrompt.isBlank()) {
                    providerMessages.add(Map.of("role", "system", "content", systemPrompt));
                }
                providerMessages.addAll(messages);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", modelCode);
                body.put("messages", providerMessages);
                body.put("temperature", temperature);
                body.put("top_p", topP);
                body.put("max_tokens", maxTokens);
                if (!responseFormat.isEmpty()) {
                    body.put("response_format", responseFormat);
                }
                applyAdditionalBodyOptions(body, options, Set.of(
                        "model", "messages", "temperature", "top_p", "max_tokens", "response_format", "timeout_sec"
                ));
                yield new ProviderRequest(joinUrl(baseUrl, "/chat/completions"), headers, body);
            }
            case "doubao" -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", modelCode);
                body.put("instructions", systemPrompt);
                body.put("input", List.of(Map.of(
                        "role", "user",
                        "content", List.of(Map.of("type", "input_text", "text", flattenMessages(messages)))
                )));
                body.put("temperature", temperature);
                body.put("top_p", topP);
                body.put("max_output_tokens", maxTokens);
                if (!responseFormat.isEmpty()) {
                    body.put("text", Map.of("format", responseFormat));
                }
                applyAdditionalBodyOptions(body, options, Set.of(
                        "model", "instructions", "input", "temperature", "top_p", "max_tokens",
                        "max_output_tokens", "response_format", "timeout_sec"
                ));
                yield new ProviderRequest(joinUrl(baseUrl, "/responses"), headers, body);
            }
            case "claude" -> {
                headers.remove(HttpHeaders.AUTHORIZATION);
                headers.put("x-api-key", required(apiKey, "api_key_secret_ref"));
                headers.put("anthropic-version", "2023-06-01");
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", modelCode);
                body.put("system", systemPrompt);
                body.put("messages", List.of(Map.of("role", "user", "content", flattenMessages(messages))));
                body.put("temperature", temperature);
                body.put("top_p", topP);
                body.put("max_tokens", maxTokens);
                applyAdditionalBodyOptions(body, options, Set.of(
                        "model", "system", "messages", "temperature", "top_p", "max_tokens", "timeout_sec"
                ));
                yield new ProviderRequest(joinUrl(baseUrl, "/messages"), headers, body);
            }
            case "gemini" -> {
                headers.remove(HttpHeaders.AUTHORIZATION);
                Map<String, Object> body = new LinkedHashMap<>();
                if (systemPrompt != null && !systemPrompt.isBlank()) {
                    body.put("system_instruction", Map.of("parts", List.of(Map.of("text", systemPrompt))));
                }
                body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", flattenMessages(messages))))));
                body.put("generationConfig", Map.of(
                        "temperature", temperature,
                        "topP", topP,
                        "maxOutputTokens", maxTokens
                ));
                applyAdditionalBodyOptions(body, options, Set.of(
                        "system_instruction", "contents", "generationConfig", "response_format", "timeout_sec"
                ));
                String url = joinUrl(baseUrl, "/models/" + urlEncode(modelCode) + ":generateContent")
                        + "?key=" + urlEncode(required(apiKey, "api_key_secret_ref"));
                yield new ProviderRequest(url, headers, body);
            }
            default -> throw stableError("UNSUPPORTED_PROVIDER", "Unsupported provider protocol: " + providerProtocol);
        };
    }

    private ProviderRequest buildProbeRequest(
            LlmProviderConfig provider,
            String providerProtocol,
            String modelCode,
            Map<String, Object> requestBodyOverride
    ) {
        String baseUrl = normalizedBaseUrl(provider);
        Map<String, String> headers = buildHeaders(provider);
        String apiKey = resolveSecretOrEmpty(provider.getApiKeySecretRef());
        if (requestBodyOverride != null && !requestBodyOverride.isEmpty()) {
            return new ProviderRequest(
                    probeEndpoint(providerProtocol, baseUrl, modelCode, apiKey),
                    probeHeaders(providerProtocol, headers, apiKey),
                    normalizeProbeBody(providerProtocol, modelCode, requestBodyOverride)
            );
        }
        return new ProviderRequest(
                probeEndpoint(providerProtocol, baseUrl, modelCode, apiKey),
                probeHeaders(providerProtocol, headers, apiKey),
                defaultProbeBody(providerProtocol, modelCode)
        );
    }

    private ProviderRequest buildEmbeddingRequest(
            LlmProviderConfig provider,
            String modelCode,
            Map<String, Object> options
    ) {
        String baseUrl = normalizedBaseUrl(provider);
        Map<String, String> headers = buildHeaders(provider);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelCode);
        body.put("input", normalizeEmbeddingInput(options));

        Object encodingFormat = options.get("encoding_format");
        if (encodingFormat != null) {
            body.put("encoding_format", encodingFormat);
        }
        Object dimensions = firstNonNull(options.get("dimensions"), options.get("embedding_dimension"));
        Integer parsedDimensions = integerValue(dimensions);
        if (parsedDimensions != null) {
            body.put("dimensions", parsedDimensions);
        }
        applyAdditionalBodyOptions(body, options, Set.of(
                "model", "input", "encoding_format", "dimensions", "embedding_dimension", "timeout_sec",
                "message", "messages", "include_messages", "single_input_as_string",
                "temperature", "top_p", "max_tokens", "max_output_tokens", "stream", "response_format"
        ));
        return new ProviderRequest(joinUrl(baseUrl, "/embeddings"), headers, body);
    }

    private Map<String, Object> postForMap(ProviderRequest providerRequest, int timeoutSec) {
        long startedAt = System.currentTimeMillis();
        if (isStreamingRequest(providerRequest.body())) {
            return postStreamForMap(providerRequest, timeoutSec, startedAt);
        }
        try {
            String endpoint = sanitizeUrlForLog(providerRequest.url());
            log.debug("model.provider.request endpoint={} timeoutSec={} bodyKeys={}", endpoint, timeoutSec, providerRequest.body().keySet());
            Map<String, Object> payload = webClient.post()
                    .uri(providerRequest.url())
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> providerRequest.headers().forEach(headers::set))
                    .bodyValue(providerRequest.body())
                    .retrieve()
                    .bodyToMono(new TypeReferenceReference())
                    .block(Duration.ofSeconds(Math.max(5, timeoutSec + 5)));
            Map<String, Object> result = payload == null ? Map.of() : payload;
            log.debug(
                    "model.provider.response endpoint={} durationMs={} responseKeys={}",
                    endpoint,
                    System.currentTimeMillis() - startedAt,
                    result.keySet()
            );
            return result;
        } catch (WebClientResponseException exception) {
            throw stableError("PROVIDER_REQUEST_FAILED", responseMessage(exception));
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw stableError("PROVIDER_REQUEST_FAILED", exception.getMessage());
        }
    }

    private Map<String, Object> postStreamForMap(ProviderRequest providerRequest, int timeoutSec, long startedAt) {
        try {
            String endpoint = sanitizeUrlForLog(providerRequest.url());
            log.debug("model.provider.stream.request endpoint={} timeoutSec={} bodyKeys={}", endpoint, timeoutSec, providerRequest.body().keySet());
            String streamBody = webClient.post()
                    .uri(providerRequest.url())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                    .headers(headers -> providerRequest.headers().forEach(headers::set))
                    .bodyValue(providerRequest.body())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(Math.max(5, timeoutSec + 5)));
            Map<String, Object> payload = openAiStreamToChatPayload(streamBody);
            log.debug(
                    "model.provider.stream.response endpoint={} durationMs={} answerLength={}",
                    endpoint,
                    System.currentTimeMillis() - startedAt,
                    extractStreamAnswer(payload).length()
            );
            return payload;
        } catch (WebClientResponseException exception) {
            throw stableError("PROVIDER_REQUEST_FAILED", responseMessage(exception));
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw stableError("PROVIDER_REQUEST_FAILED", exception.getMessage());
        }
    }

    private String sanitizeUrlForLog(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(url);
            String query = uri.getRawQuery();
            if (query == null || query.isBlank()) {
                return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, null).toString();
            }
            String sanitizedQuery = java.util.Arrays.stream(query.split("&"))
                    .map(parameter -> {
                        int separatorIndex = parameter.indexOf('=');
                        String key = separatorIndex < 0 ? parameter : parameter.substring(0, separatorIndex);
                        String normalizedKey = key.toLowerCase(Locale.ROOT);
                        if (normalizedKey.contains("key")
                                || normalizedKey.contains("token")
                                || normalizedKey.contains("secret")
                                || normalizedKey.contains("password")) {
                            return key + "=***";
                        }
                        return parameter;
                    })
                    .collect(java.util.stream.Collectors.joining("&"));
            return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), sanitizedQuery, null).toString();
        } catch (Exception exception) {
            int queryIndex = url.indexOf('?');
            return queryIndex < 0 ? url : url.substring(0, queryIndex) + "?***";
        }
    }

    private String extractText(String providerProtocol, Map<String, Object> payload) {
        try {
            switch (providerProtocol) {
                case "openai", "openai_compatible", "deepseek", "qwen", "custom" -> {
                    Object choices = payload.get("choices");
                    if (choices instanceof List<?> list && !list.isEmpty()) {
                        Object first = list.get(0);
                        if (first instanceof Map<?, ?> map) {
                            Object message = map.get("message");
                            if (message instanceof Map<?, ?> messageMap && messageMap.get("content") != null) {
                                return String.valueOf(messageMap.get("content"));
                            }
                        }
                    }
                }
                case "doubao" -> {
                    String text = extractDoubaoText(payload);
                    if (text != null) {
                        return text;
                    }
                }
                case "claude" -> {
                    Object content = payload.get("content");
                    if (content instanceof List<?> list && !list.isEmpty()) {
                        Object first = list.get(0);
                        if (first instanceof Map<?, ?> map && map.get("text") != null) {
                            return String.valueOf(map.get("text"));
                        }
                    }
                }
                case "gemini" -> {
                    Object candidates = payload.get("candidates");
                    if (candidates instanceof List<?> list && !list.isEmpty()) {
                        Object first = list.get(0);
                        if (first instanceof Map<?, ?> candidateMap) {
                            Object content = candidateMap.get("content");
                            if (content instanceof Map<?, ?> contentMap) {
                                Object parts = contentMap.get("parts");
                                if (parts instanceof List<?> partList && !partList.isEmpty()) {
                                    Object firstPart = partList.get(0);
                                    if (firstPart instanceof Map<?, ?> partMap && partMap.get("text") != null) {
                                        return String.valueOf(partMap.get("text"));
                                    }
                                }
                            }
                        }
                    }
                }
                default -> {
                }
            }
        } catch (Exception ignored) {
        }
        throw stableError("PROVIDER_RESPONSE_INVALID", "Provider returned invalid chat response");
    }

    private String extractDoubaoText(Map<String, Object> payload) {
        String outputText = stringValue(payload.get("output_text"));
        if (outputText != null) {
            return outputText;
        }
        Object output = payload.get("output");
        if (!(output instanceof List<?> outputItems) || outputItems.isEmpty()) {
            return null;
        }
        String messageOutputText = extractDoubaoOutputText(outputItems, true);
        return messageOutputText == null ? extractDoubaoOutputText(outputItems, false) : messageOutputText;
    }

    private String extractDoubaoOutputText(List<?> outputItems, boolean requireOutputTextType) {
        List<String> parts = new ArrayList<>();
        for (Object outputItem : outputItems) {
            if (!(outputItem instanceof Map<?, ?> outputMap)) {
                continue;
            }
            if (!"message".equals(String.valueOf(outputMap.get("type")))
                    && !"assistant".equals(String.valueOf(outputMap.get("role")))) {
                continue;
            }
            Object content = outputMap.get("content");
            if (!(content instanceof List<?> contentItems)) {
                continue;
            }
            for (Object contentItem : contentItems) {
                if (!(contentItem instanceof Map<?, ?> contentMap)) {
                    continue;
                }
                if (requireOutputTextType && !"output_text".equals(String.valueOf(contentMap.get("type")))) {
                    continue;
                }
                String text = stringValue(contentMap.get("text"));
                if (text != null) {
                    parts.add(text);
                }
            }
        }
        return parts.isEmpty() ? null : String.join("", parts);
    }

    private Map<String, Object> extractUsage(String providerProtocol, Map<String, Object> payload) {
        Map<String, Object> usage = asMap(payload.get("usage"));
        if (!usage.isEmpty()) {
            return usage;
        }
        if ("gemini".equals(providerProtocol)) {
            Map<String, Object> metadata = asMap(payload.get("usageMetadata"));
            if (!metadata.isEmpty()) {
                return metadata;
            }
        }
        return Map.of();
    }

    private Map<String, Object> openAiStreamToChatPayload(String streamBody) {
        StringBuilder answer = new StringBuilder();
        Map<String, Object> usage = new LinkedHashMap<>();
        if (streamBody != null && !streamBody.isBlank()) {
            for (String item : streamDataItems(streamBody)) {
                if ("[DONE]".equals(item)) {
                    break;
                }
                try {
                    JsonNode node = objectMapper.readTree(item);
                    JsonNode usageNode = node.get("usage");
                    if (usageNode != null && usageNode.isObject()) {
                        usage = objectMapper.convertValue(usageNode, new TypeReference<Map<String, Object>>() {});
                    }
                    String delta = extractOpenAiStreamDelta(node);
                    if (delta != null && !delta.isEmpty()) {
                        answer.append(delta);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("choices", List.of(Map.of("message", Map.of("content", answer.toString()))));
        if (!usage.isEmpty()) {
            payload.put("usage", usage);
        }
        return payload;
    }

    private List<String> streamDataItems(String streamBody) {
        List<String> items = new ArrayList<>();
        String[] lines = streamBody.split("\\R");
        for (String line : lines) {
            String text = line == null ? "" : line.trim();
            if (text.isEmpty()) {
                continue;
            }
            if (text.startsWith("data:")) {
                items.add(text.substring("data:".length()).trim());
            } else if (text.startsWith("{") || "[DONE]".equals(text)) {
                items.add(text);
            }
        }
        return items;
    }

    private String extractOpenAiStreamDelta(JsonNode node) {
        JsonNode choices = node.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return null;
        }
        JsonNode first = choices.get(0);
        JsonNode delta = first.get("delta");
        if (delta != null && delta.isObject()) {
            JsonNode content = delta.get("content");
            if (content != null && !content.isNull()) {
                return content.asText();
            }
        }
        JsonNode message = first.get("message");
        if (message != null && message.isObject()) {
            JsonNode content = message.get("content");
            if (content != null && !content.isNull()) {
                return content.asText();
            }
        }
        return null;
    }

    private String extractStreamAnswer(Map<String, Object> payload) {
        try {
            Object choices = payload.get("choices");
            if (choices instanceof List<?> list && !list.isEmpty()) {
                Object first = list.get(0);
                if (first instanceof Map<?, ?> choice) {
                    Object message = choice.get("message");
                    if (message instanceof Map<?, ?> messageMap && messageMap.get("content") != null) {
                        return String.valueOf(messageMap.get("content"));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private List<Map<String, Object>> normalizeMessages(List<? extends Map<String, ?>> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of(Map.of("role", "user", "content", "ping"));
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, ?> message : messages) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("role", firstNonBlank(stringValue(message.get("role")), "user"));
            value.put("content", firstNonBlank(stringValue(message.get("content")), ""));
            normalized.add(value);
        }
        return normalized;
    }

    private Object normalizeEmbeddingInput(Map<String, Object> options) {
        if (options == null || options.isEmpty()) {
            return "ping";
        }
        Object input = options.get("input");
        if (input != null) {
            return input;
        }
        Object messages = options.get("messages");
        if (messages instanceof List<?> list && !list.isEmpty()) {
            return flattenArbitraryMessages(list);
        }
        Object message = options.get("message");
        if (message != null) {
            return message;
        }
        return "ping";
    }

    private Map<String, Object> mergeOptions(Map<String, Object> defaults, Map<String, Object> overrides) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (defaults != null) {
            merged.putAll(defaults);
        }
        if (overrides != null) {
            merged.putAll(overrides);
        }
        return merged;
    }

    private Object firstNonNull(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private void applyAdditionalBodyOptions(Map<String, Object> body, Map<String, Object> options, Set<String> protectedKeys) {
        if (options == null || options.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : options.entrySet()) {
            if (entry.getKey() == null || protectedKeys.contains(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            body.put(entry.getKey(), entry.getValue());
        }
    }

    private Map<String, String> buildHeaders(LlmProviderConfig provider) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        String secretRef = provider.getApiKeySecretRef();
        if (secretRef != null && !secretRef.isBlank()) {
            headers.put(HttpHeaders.AUTHORIZATION, "Bearer " + required(resolveSecretOrEmpty(secretRef), "api_key_secret_ref"));
        }
        return headers;
    }

    private Map<String, String> probeHeaders(String providerProtocol, Map<String, String> headers, String apiKey) {
        Map<String, String> probeHeaders = new LinkedHashMap<>(headers);
        if ("claude".equals(providerProtocol)) {
            probeHeaders.remove(HttpHeaders.AUTHORIZATION);
            probeHeaders.put("x-api-key", required(apiKey, "api_key_secret_ref"));
            probeHeaders.put("anthropic-version", "2023-06-01");
        } else if ("gemini".equals(providerProtocol)) {
            probeHeaders.remove(HttpHeaders.AUTHORIZATION);
        }
        return probeHeaders;
    }

    private String probeEndpoint(String providerProtocol, String baseUrl, String modelCode, String apiKey) {
        return switch (providerProtocol) {
            case "openai", "openai_compatible", "deepseek", "qwen", "custom" -> joinUrl(baseUrl, "/chat/completions");
            case "doubao" -> joinUrl(baseUrl, "/responses");
            case "claude" -> joinUrl(baseUrl, "/messages");
            case "gemini" -> joinUrl(baseUrl, "/models/" + urlEncode(modelCode) + ":generateContent")
                    + "?key=" + urlEncode(required(apiKey, "api_key_secret_ref"));
            default -> throw stableError("UNSUPPORTED_PROVIDER", "Unsupported provider protocol: " + providerProtocol);
        };
    }

    private Map<String, Object> defaultProbeBody(String providerProtocol, String modelCode) {
        return switch (providerProtocol) {
            case "openai", "openai_compatible", "deepseek", "qwen", "custom" -> Map.of(
                    "model", modelCode,
                    "messages", List.of(Map.of("role", "user", "content", "connectivity test")),
                    "max_tokens", 32
            );
            case "doubao" -> Map.of(
                    "model", modelCode,
                    "input", List.of(Map.of(
                            "role", "user",
                            "content", List.of(Map.of("type", "input_text", "text", "connectivity test"))
                    ))
            );
            case "claude" -> Map.of(
                    "model", modelCode,
                    "messages", List.of(Map.of("role", "user", "content", "connectivity test")),
                    "max_tokens", 32
            );
            case "gemini" -> Map.of(
                    "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", "connectivity test"))))
            );
            default -> throw stableError("UNSUPPORTED_PROVIDER", "Unsupported provider protocol: " + providerProtocol);
        };
    }

    private Map<String, Object> normalizeProbeBody(String providerProtocol, String modelCode, Map<String, Object> requestBodyOverride) {
        if (!"doubao".equals(providerProtocol) || requestBodyOverride.get("input") != null) {
            return requestBodyOverride;
        }
        Object messages = requestBodyOverride.get("messages");
        if (!(messages instanceof List<?> list) || list.isEmpty()) {
            return requestBodyOverride;
        }
        Map<String, Object> normalized = new LinkedHashMap<>(requestBodyOverride);
        normalized.remove("messages");
        normalized.put("model", modelCode);
        normalized.put("input", List.of(Map.of(
                "role", "user",
                "content", List.of(Map.of("type", "input_text", "text", flattenArbitraryMessages(list)))
        )));
        return normalized;
    }

    private String flattenMessages(List<Map<String, Object>> messages) {
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> message : messages) {
            String content = stringValue(message.get("content"));
            if (content != null && !content.isBlank()) {
                parts.add(content);
            }
        }
        return String.join("\n", parts);
    }

    private List<List<Double>> extractEmbeddingVectors(Map<String, Object> payload) {
        Object data = payload.get("data");
        if (!(data instanceof List<?> list) || list.isEmpty()) {
            throw stableError("PROVIDER_RESPONSE_INVALID", "Provider returned invalid embedding response");
        }
        List<List<Double>> vectors = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw stableError("PROVIDER_RESPONSE_INVALID", "Provider returned invalid embedding response");
            }
            Object embedding = map.get("embedding");
            if (!(embedding instanceof List<?> embeddingList) || embeddingList.isEmpty()) {
                throw stableError("PROVIDER_RESPONSE_INVALID", "Provider returned invalid embedding response");
            }
            List<Double> vector = new ArrayList<>();
            for (Object value : embeddingList) {
                double parsed = decimalValue(value, Double.NaN);
                if (Double.isNaN(parsed)) {
                    throw stableError("PROVIDER_RESPONSE_INVALID", "Provider returned invalid embedding response");
                }
                vector.add(parsed);
            }
            vectors.add(vector);
        }
        return vectors;
    }

    private String summarizeEmbeddingResponse(List<List<Double>> vectors) {
        if (vectors == null || vectors.isEmpty()) {
            throw stableError("PROVIDER_RESPONSE_INVALID", "Provider returned invalid embedding response");
        }
        int dimension = vectors.get(0).size();
        if (dimension <= 0) {
            throw stableError("PROVIDER_RESPONSE_INVALID", "Provider returned invalid embedding response");
        }
        return "Embedding call succeeded: " + vectors.size() + " vector(s), dimension " + dimension;
    }

    private String flattenArbitraryMessages(List<?> messages) {
        List<String> parts = new ArrayList<>();
        for (Object message : messages) {
            if (message instanceof Map<?, ?> map && map.get("content") != null) {
                String content = String.valueOf(map.get("content"));
                if (!content.isBlank()) {
                    parts.add(content);
                }
            }
        }
        return String.join("\n", parts);
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.isTextual()) {
                node = objectMapper.readTree(node.asText());
            }
            return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
        } catch (Exception exception) {
            return new LinkedHashMap<>();
        }
    }

    private Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private boolean isStreamingRequest(Map<String, Object> body) {
        Object stream = body.get("stream");
        if (stream instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return stream != null && "true".equalsIgnoreCase(String.valueOf(stream).trim());
    }

    private String normalizedBaseUrl(LlmProviderConfig provider) {
        return required(provider.getBaseUrl(), "base_url").replaceAll("/+$", "");
    }

    private String resolveProviderProtocol(String providerType) {
        return "custom".equals(providerType) ? "openai" : providerType;
    }

    private String normalizeProviderType(String providerType) {
        return providerType == null ? "" : providerType.trim().toLowerCase(Locale.ROOT);
    }

    private String resolveSecretOrEmpty(String secretRef) {
        if (secretRef == null || secretRef.isBlank()) {
            return "";
        }
        if (secretRef.startsWith("env:")) {
            String envName = secretRef.substring("env:".length());
            String value = System.getenv(envName);
            if (value == null || value.isBlank()) {
                throw stableError("PROVIDER_REQUEST_FAILED", "Missing environment secret: " + envName);
            }
            return value;
        }
        return secretRef;
    }

    private String joinUrl(String baseUrl, String path) {
        String normalizedBaseUrl = baseUrl.replaceAll("/+$", "");
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        if (normalizedBaseUrl.endsWith(normalizedPath)) {
            return normalizedBaseUrl;
        }
        return normalizedBaseUrl + normalizedPath;
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String responseMessage(WebClientResponseException exception) {
        String body = exception.getResponseBodyAsString();
        return body == null || body.isBlank() ? exception.getMessage() : body;
    }

    private ResponseStatusException stableError(String code, String message) {
        return new ResponseStatusException(BAD_REQUEST, code + ": " + message);
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, field + " is required");
        }
        return value.trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private double decimalValue(Object value, double defaultValue) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.doubleValue();
        }
        try {
            return value == null ? defaultValue : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    private static final class TypeReferenceReference extends org.springframework.core.ParameterizedTypeReference<Map<String, Object>> {
    }

    private record ProviderRequest(
            String url,
            Map<String, String> headers,
            Map<String, Object> body
    ) {
    }
}
