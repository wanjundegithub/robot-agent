package robot.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import robot.agent.dto.request.TestModelProfileRequest;
import robot.agent.dto.request.UpsertModelProfileRequest;
import robot.agent.dto.request.UpsertModelProviderRequest;
import robot.agent.dto.request.ValidateModelProviderRequest;
import robot.agent.model.LlmModelProfile;
import robot.agent.model.LlmProviderConfig;
import robot.agent.repository.LlmModelProfileRepository;
import robot.agent.repository.LlmProviderConfigRepository;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Service
@Transactional(readOnly = true)
public class ModelConfigService {

    private final LlmProviderConfigRepository providerRepository;
    private final LlmModelProfileRepository profileRepository;
    private final ObjectMapper objectMapper;
    private final AccessControlService accessControlService;
    private final AuditService auditService;
    private final WebClient webClient;

    public ModelConfigService(
            LlmProviderConfigRepository providerRepository,
            LlmModelProfileRepository profileRepository,
            ObjectMapper objectMapper,
            AccessControlService accessControlService,
            AuditService auditService
    ) {
        this.providerRepository = providerRepository;
        this.profileRepository = profileRepository;
        this.objectMapper = objectMapper;
        this.accessControlService = accessControlService;
        this.auditService = auditService;
        this.webClient = WebClient.builder().build();
    }

    public RuntimeModelBundle buildRuntimeBundle(Collection<Map<String, Object>> workflowDefinitions, String routingProfileCode) {
        Set<String> profileCodes = new LinkedHashSet<>();
        if (routingProfileCode != null && !routingProfileCode.isBlank()) {
            profileCodes.add(routingProfileCode);
        }
        for (Map<String, Object> definition : workflowDefinitions) {
            collectProfileRefs(definition, profileCodes);
        }

        List<LlmModelProfile> profiles = profileRepository.findByProfileCodeIn(profileCodes);
        Map<String, LlmModelProfile> profileByCode = new LinkedHashMap<>();
        for (LlmModelProfile profile : profiles) {
            profileByCode.put(profile.getProfileCode(), profile);
            if (profile.getFallbackProfileCode() != null && !profile.getFallbackProfileCode().isBlank()) {
                profileRepository.findByProfileCode(profile.getFallbackProfileCode())
                        .ifPresent(fallback -> profileByCode.put(fallback.getProfileCode(), fallback));
            }
        }

        Set<String> providerCodes = new LinkedHashSet<>();
        for (LlmModelProfile profile : profileByCode.values()) {
            providerCodes.add(profile.getProviderCode());
        }
        List<LlmProviderConfig> providers = providerRepository.findByProviderCodeIn(providerCodes);
        return new RuntimeModelBundle(
                providers.stream().map(this::providerToRuntimeMap).toList(),
                profileByCode.values().stream().map(this::profileToRuntimeMap).toList()
        );
    }

    public List<Map<String, Object>> getProviderConfigs() {
        return providerRepository.findAll().stream()
                .sorted(Comparator.comparing(LlmProviderConfig::getProviderCode, String.CASE_INSENSITIVE_ORDER))
                .map(this::providerToResponseMap)
                .toList();
    }

    public List<Map<String, Object>> getModelProfiles() {
        return profileRepository.findAll().stream()
                .sorted(Comparator.comparing(LlmModelProfile::getProfileCode, String.CASE_INSENSITIVE_ORDER))
                .map(this::profileToResponseMap)
                .toList();
    }

    @Transactional
    public Map<String, Object> saveProviderConfig(String userId, UpsertModelProviderRequest request) {
        requireAdmin(userId, "model.provider.create");
        String providerCode = required(request.getProviderCode(), "provider_code");
        if (providerRepository.findByProviderCode(providerCode).isPresent()) {
            throw new ResponseStatusException(BAD_REQUEST, "Provider already exists: " + providerCode);
        }
        LlmProviderConfig provider = new LlmProviderConfig();
        applyProviderRequest(provider, request, true);
        provider.setCreatedBy(normalizeUserId(userId));
        provider.setCreatedAt(LocalDateTime.now());
        provider.setUpdatedAt(LocalDateTime.now());
        LlmProviderConfig saved = providerRepository.save(provider);
        auditService.logAction(1L, normalizeUserId(userId), "model.provider.create", "llm_provider_config", providerCode, null, 200);
        return providerToResponseMap(saved);
    }

    @Transactional
    public Map<String, Object> updateProviderConfig(String userId, String providerCode, UpsertModelProviderRequest request) {
        requireAdmin(userId, "model.provider.update");
        LlmProviderConfig provider = providerRepository.findByProviderCode(providerCode)
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "Provider not found: " + providerCode));
        applyProviderRequest(provider, request, false);
        provider.setUpdatedAt(LocalDateTime.now());
        LlmProviderConfig saved = providerRepository.save(provider);
        auditService.logAction(1L, normalizeUserId(userId), "model.provider.update", "llm_provider_config", providerCode, null, 200);
        return providerToResponseMap(saved);
    }

    public Map<String, Object> validateProviderConfig(String userId, String providerCode, ValidateModelProviderRequest request) {
        requireAdmin(userId, "model.provider.validate");
        LlmProviderConfig provider = providerRepository.findByProviderCode(providerCode)
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "Provider not found: " + providerCode));
        String modelCode = resolveValidationModelCode(providerCode, request);
        int statusCode = probeProviderConnection(provider, modelCode, request.getRequestBody());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", true);
        result.put("provider_code", providerCode);
        result.put("status_code", statusCode);
        result.put("tested_model_code", modelCode);
        result.put("message", "联通性测试成功");
        return result;
    }

    public Map<String, Object> validateProviderDraft(String userId, ValidateModelProviderRequest request) {
        requireAdmin(userId, "model.provider.validate");
        LlmProviderConfig draftProvider = new LlmProviderConfig();
        applyProviderValidationRequest(draftProvider, request);

        String modelCode = required(firstNonBlank(request.getModelCode(), draftProvider.getDefaultModelCode()), "model_code");
        int statusCode = probeProviderConnection(draftProvider, modelCode, request.getRequestBody());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", true);
        result.put("provider_code", "draft-provider");
        result.put("status_code", statusCode);
        result.put("tested_model_code", modelCode);
        result.put("message", "联通性测试成功");
        return result;
    }

    @Transactional
    public Map<String, Object> saveModelProfile(String userId, UpsertModelProfileRequest request) {
        requireAdmin(userId, "model.profile.create");
        String profileCode = required(request.getProfileCode(), "profile_code");
        if (profileRepository.findByProfileCode(profileCode).isPresent()) {
            throw new ResponseStatusException(BAD_REQUEST, "Profile already exists: " + profileCode);
        }
        LlmModelProfile profile = new LlmModelProfile();
        applyProfileRequest(profile, request, true);
        profile.setCreatedBy(normalizeUserId(userId));
        profile.setCreatedAt(LocalDateTime.now());
        profile.setUpdatedAt(LocalDateTime.now());
        LlmModelProfile saved = profileRepository.save(profile);
        auditService.logAction(1L, normalizeUserId(userId), "model.profile.create", "llm_model_profile", profileCode, null, 200);
        return profileToResponseMap(saved);
    }

    @Transactional
    public Map<String, Object> updateModelProfile(String userId, String profileCode, UpsertModelProfileRequest request) {
        requireAdmin(userId, "model.profile.update");
        LlmModelProfile profile = profileRepository.findByProfileCode(profileCode)
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "Profile not found: " + profileCode));
        applyProfileRequest(profile, request, false);
        profile.setUpdatedAt(LocalDateTime.now());
        LlmModelProfile saved = profileRepository.save(profile);
        auditService.logAction(1L, normalizeUserId(userId), "model.profile.update", "llm_model_profile", profileCode, null, 200);
        return profileToResponseMap(saved);
    }

    public Map<String, Object> testProfileChat(String userId, String profileCode, TestModelProfileRequest request) {
        requireAdmin(userId, "model.profile.test_chat");
        LlmModelProfile profile = profileRepository.findByProfileCode(profileCode)
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "Profile not found: " + profileCode));
        LlmProviderConfig provider = providerRepository.findByProviderCode(profile.getProviderCode())
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "Provider not found: " + profile.getProviderCode()));
        ProfilePreset preset = profilePresetForPurpose(profile.getPurpose());

        Map<String, Object> responseFormat = parseJson(profile.getResponseFormat());
        String answer = invokeChatCompletion(
                provider,
                effectiveModelCode(provider, profile),
                request.getSystemPrompt(),
                request.getMessage(),
                coalesce(profile.getTimeoutSec(), preset.timeoutSec(), 30),
                coalesce(profile.getTemperature(), preset.temperature(), BigDecimal.valueOf(0.30d)),
                coalesce(profile.getTopP(), preset.topP(), BigDecimal.ONE),
                coalesce(profile.getMaxTokens(), preset.maxTokens(), 256),
                responseFormat.isEmpty() ? null : responseFormat
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("profile_code", profileCode);
        result.put("provider_code", provider.getProviderCode());
        result.put("model_code", effectiveModelCode(provider, profile));
        result.put("answer", answer);
        return result;
    }

    public String resolveRoutingProfileCode(Collection<Map<String, Object>> workflowDefinitions) {
        for (Map<String, Object> definition : workflowDefinitions) {
            Object config = definition.get("config");
            if (config instanceof Map<?, ?> configMap) {
                Object profileCode = configMap.get("intent_profile_ref");
                if (profileCode != null && !String.valueOf(profileCode).isBlank()) {
                    return String.valueOf(profileCode);
                }
            }
        }
        return "intent-router-v1";
    }

    private void collectProfileRefs(Object source, Set<String> refs) {
        if (source instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    String key = String.valueOf(entry.getKey());
                    if (key.endsWith("_profile_ref") && entry.getValue() != null && !String.valueOf(entry.getValue()).isBlank()) {
                        refs.add(String.valueOf(entry.getValue()));
                    }
                }
                collectProfileRefs(entry.getValue(), refs);
            }
            return;
        }
        if (source instanceof List<?> list) {
            for (Object item : list) {
                collectProfileRefs(item, refs);
            }
        }
    }

    private Map<String, Object> providerToRuntimeMap(LlmProviderConfig provider) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("provider_code", provider.getProviderCode());
        value.put("provider_name", provider.getProviderName());
        value.put("provider_type", provider.getProviderType());
        value.put("base_url", provider.getBaseUrl());
        value.put("default_model_code", provider.getDefaultModelCode());
        value.put("api_key_secret_ref", provider.getApiKeySecretRef());
        value.put("enabled", provider.isEnabled());
        return value;
    }

    private Map<String, Object> providerToResponseMap(LlmProviderConfig provider) {
        Map<String, Object> value = providerToRuntimeMap(provider);
        value.remove("api_key_secret_ref");
        value.put("api_key_mode", secretMode(provider.getApiKeySecretRef()));
        value.put("api_key_configured", isSecretAvailable(provider.getApiKeySecretRef()));
        value.put("api_key_masked", maskSecret(provider.getApiKeySecretRef()));
        if (secretMissingReason(provider.getApiKeySecretRef()) != null) {
            value.put("api_key_error", secretMissingReason(provider.getApiKeySecretRef()));
        }
        value.put("created_at", provider.getCreatedAt());
        value.put("updated_at", provider.getUpdatedAt());
        return value;
    }

    private Map<String, Object> profileToRuntimeMap(LlmModelProfile profile) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("profile_code", profile.getProfileCode());
        value.put("provider_code", profile.getProviderCode());
        value.put("model_code", resolveEffectiveModelCode(profile));
        value.put("purpose", profile.getPurpose());
        value.put("temperature", profile.getTemperature());
        value.put("top_p", profile.getTopP());
        value.put("max_tokens", profile.getMaxTokens());
        value.put("timeout_sec", profile.getTimeoutSec());
        value.put("response_format", parseJson(profile.getResponseFormat()));
        value.put("fallback_profile_code", profile.getFallbackProfileCode());
        value.put("enabled", profile.isEnabled());
        return value;
    }

    private Map<String, Object> profileToResponseMap(LlmModelProfile profile) {
        Map<String, Object> value = profileToRuntimeMap(profile);
        value.put("created_at", profile.getCreatedAt());
        value.put("updated_at", profile.getUpdatedAt());
        return value;
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

    private void applyProviderRequest(LlmProviderConfig provider, UpsertModelProviderRequest request, boolean creating) {
        if (creating) {
            provider.setProviderCode(required(request.getProviderCode(), "provider_code"));
        }
        provider.setProviderName(required(request.getProviderName(), "provider_name"));
        provider.setProviderType(required(request.getProviderType(), "provider_type"));
        provider.setBaseUrl(required(request.getBaseUrl(), "base_url").replaceAll("/+$", ""));
        provider.setDefaultModelCode(required(request.getDefaultModelCode(), "default_model_code"));
        if (request.getApiKeySecretRef() != null) {
            provider.setApiKeySecretRef(blankToNull(request.getApiKeySecretRef()));
        }
        if (request.getEnabled() != null) {
            provider.setEnabled(request.getEnabled());
        }
    }

    private void applyProviderValidationRequest(LlmProviderConfig provider, ValidateModelProviderRequest request) {
        provider.setProviderType(required(request.getProviderType(), "provider_type"));
        provider.setBaseUrl(required(request.getBaseUrl(), "base_url").replaceAll("/+$", ""));
        provider.setDefaultModelCode(required(request.getDefaultModelCode(), "default_model_code"));
        provider.setApiKeySecretRef(blankToNull(request.getApiKeySecretRef()));
        provider.setEnabled(true);
    }

    private void applyProfileRequest(LlmModelProfile profile, UpsertModelProfileRequest request, boolean creating) {
        if (creating) {
            profile.setProfileCode(required(request.getProfileCode(), "profile_code"));
        }
        String providerCode = required(request.getProviderCode(), "provider_code");
        LlmProviderConfig provider = providerRepository.findByProviderCode(providerCode)
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "Provider not found: " + providerCode));
        profile.setProviderCode(providerCode);
        profile.setModelCode(required(provider.getDefaultModelCode(), "default_model_code"));
        String purpose = required(request.getPurpose(), "purpose");
        ProfilePreset preset = profilePresetForPurpose(purpose);
        profile.setPurpose(purpose);
        profile.setTemperature(coalesce(preset.temperature(), request.getTemperature(), profile.getTemperature(), BigDecimal.valueOf(0.30d)));
        profile.setTopP(coalesce(preset.topP(), request.getTopP(), profile.getTopP(), BigDecimal.ONE));
        profile.setMaxTokens(coalesce(preset.maxTokens(), request.getMaxTokens(), profile.getMaxTokens(), 1024));
        profile.setTimeoutSec(coalesce(preset.timeoutSec(), request.getTimeoutSec(), profile.getTimeoutSec(), 30));
        profile.setResponseFormat(writeJson(request.getResponseFormat()));
        profile.setFallbackProfileCode(blankToNull(request.getFallbackProfileCode()));
        if (request.getEnabled() != null) {
            profile.setEnabled(request.getEnabled());
        }
    }

    private String invokeChatCompletion(
            LlmProviderConfig provider,
            String modelCode,
            String systemPrompt,
            String message,
            int timeoutSec,
            BigDecimal temperature,
            BigDecimal topP,
            int maxTokens,
            Map<String, Object> responseFormat
    ) {
        String providerType = normalizeProviderType(required(provider.getProviderType(), "provider_type"));
        String providerProtocol = resolveProviderProtocol(providerType);
        ProviderRequest providerRequest = buildProviderRequest(
                provider,
                providerProtocol,
                modelCode,
                systemPrompt,
                message,
                temperature,
                topP,
                maxTokens,
                responseFormat
        );
        Map<String, Object> payload;
        try {
            payload = webClient.post()
                    .uri(providerRequest.url())
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(httpHeaders -> providerRequest.headers().forEach(httpHeaders::set))
                    .bodyValue(providerRequest.body())
                    .retrieve()
                    .bodyToMono(new TypeReferenceReference())
                    .block(Duration.ofSeconds(Math.max(5, timeoutSec + 5)));
        } catch (WebClientResponseException exception) {
            String responseBody = exception.getResponseBodyAsString();
            String errorMessage = responseBody == null || responseBody.isBlank()
                    ? exception.getMessage()
                    : responseBody;
            throw new ResponseStatusException(BAD_REQUEST, "Provider request failed: " + errorMessage, exception);
        } catch (Exception exception) {
            throw new ResponseStatusException(BAD_REQUEST, "Provider request failed: " + exception.getMessage(), exception);
        }

        return extractProviderText(providerProtocol, payload);
    }

    private String resolveValidationModelCode(String providerCode, ValidateModelProviderRequest request) {
        if (request.getModelCode() != null && !request.getModelCode().isBlank()) {
            return request.getModelCode().trim();
        }
        if (request.getPurpose() != null && !request.getPurpose().isBlank()) {
            Optional<String> modelCode = profileRepository.findAll().stream()
                    .filter(profile -> providerCode.equals(profile.getProviderCode()))
                    .filter(profile -> request.getPurpose().trim().equalsIgnoreCase(profile.getPurpose()))
                    .map(LlmModelProfile::getModelCode)
                    .filter(Objects::nonNull)
                    .filter(value -> !value.isBlank())
                    .findFirst();
            if (modelCode.isPresent()) {
                return modelCode.get();
            }
        }
        return profileRepository.findAll().stream()
                .filter(profile -> providerCode.equals(profile.getProviderCode()))
                .map(LlmModelProfile::getModelCode)
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElseGet(() -> defaultModelCodeForProvider(providerCode));
    }

    private int probeProviderConnection(LlmProviderConfig provider, String modelCode, Map<String, Object> requestBodyOverride) {
        String providerType = normalizeProviderType(required(provider.getProviderType(), "provider_type"));
        String providerProtocol = resolveProviderProtocol(providerType);
        ProviderRequest probeRequest = buildProviderProbeRequest(provider, providerProtocol, modelCode, requestBodyOverride);

        try {
            ResponseEntity<Void> response = webClient.post()
                    .uri(probeRequest.url())
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(httpHeaders -> probeRequest.headers().forEach(httpHeaders::set))
                    .bodyValue(probeRequest.body())
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(20));
            if (response == null || !response.getStatusCode().is2xxSuccessful()) {
                throw new ResponseStatusException(BAD_REQUEST, "Provider request failed");
            }
            return response.getStatusCode().value();
        } catch (WebClientResponseException exception) {
            String responseBody = exception.getResponseBodyAsString();
            String errorMessage = responseBody == null || responseBody.isBlank()
                    ? exception.getMessage()
                    : responseBody;
            throw new ResponseStatusException(BAD_REQUEST, "Provider request failed: " + errorMessage, exception);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(BAD_REQUEST, "Provider request failed: " + exception.getMessage(), exception);
        }
    }

    private Map<String, String> buildProviderHeaders(LlmProviderConfig provider) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        String secretRef = provider.getApiKeySecretRef();
        if (secretRef != null && !secretRef.isBlank()) {
            headers.put(HttpHeaders.AUTHORIZATION, "Bearer " + resolveSecret(secretRef));
        }
        return headers;
    }

    private ProviderRequest buildProviderRequest(
            LlmProviderConfig provider,
            String providerProtocol,
            String modelCode,
            String systemPrompt,
            String message,
            BigDecimal temperature,
            BigDecimal topP,
            int maxTokens,
            Map<String, Object> responseFormat
    ) {
        String baseUrl = required(provider.getBaseUrl(), "base_url").replaceAll("/+$", "");
        String normalizedModelCode = required(modelCode, "model_code");
        String normalizedSystemPrompt = defaultString(systemPrompt, "你是服务机器人模型连通性校验助手，请简洁回答。");
        String normalizedMessage = defaultString(message, "请回复：模型连接测试成功");
        double normalizedTemperature = coalesce(temperature, BigDecimal.valueOf(0.20d)).doubleValue();
        double normalizedTopP = coalesce(topP, BigDecimal.ONE).doubleValue();
        int normalizedMaxTokens = Math.max(32, maxTokens);
        Map<String, String> headers = buildProviderHeaders(provider);
        Map<String, Object> body = new LinkedHashMap<>();
        String apiKey = provider.getApiKeySecretRef() == null || provider.getApiKeySecretRef().isBlank()
                ? ""
                : resolveSecret(provider.getApiKeySecretRef());

        switch (providerProtocol) {
            case "openai", "openai_compatible", "deepseek", "qwen", "custom" -> {
                body.put("model", normalizedModelCode);
                body.put("messages", List.of(
                        Map.of("role", "system", "content", normalizedSystemPrompt),
                        Map.of("role", "user", "content", normalizedMessage)
                ));
                body.put("temperature", normalizedTemperature);
                body.put("top_p", normalizedTopP);
                body.put("max_tokens", normalizedMaxTokens);
                if (responseFormat != null && !responseFormat.isEmpty()) {
                    body.put("response_format", responseFormat);
                }
                return new ProviderRequest(joinUrl(baseUrl, "/chat/completions"), headers, body);
            }
            case "doubao" -> {
                body.put("model", normalizedModelCode);
                body.put("instructions", normalizedSystemPrompt);
                body.put("input", List.of(
                        Map.of(
                                "role", "user",
                                "content", List.of(
                                        Map.of("type", "input_text", "text", normalizedMessage)
                                )
                        )
                ));
                body.put("temperature", normalizedTemperature);
                body.put("top_p", normalizedTopP);
                body.put("max_output_tokens", normalizedMaxTokens);
                if (responseFormat != null && !responseFormat.isEmpty()) {
                    body.put("text", Map.of("format", responseFormat));
                }
                return new ProviderRequest(joinUrl(baseUrl, "/responses"), headers, body);
            }
            case "claude" -> {
                headers.put("x-api-key", required(apiKey, "api_key_secret_ref"));
                headers.put("anthropic-version", "2023-06-01");
                headers.remove(HttpHeaders.AUTHORIZATION);
                body.put("model", normalizedModelCode);
                body.put("system", normalizedSystemPrompt);
                body.put("messages", List.of(Map.of("role", "user", "content", normalizedMessage)));
                body.put("max_tokens", normalizedMaxTokens);
                body.put("temperature", normalizedTemperature);
                body.put("top_p", normalizedTopP);
                return new ProviderRequest(joinUrl(baseUrl, "/messages"), headers, body);
            }
            case "gemini" -> {
                headers.remove(HttpHeaders.AUTHORIZATION);
                body.put("system_instruction", Map.of(
                        "parts", List.of(Map.of("text", normalizedSystemPrompt))
                ));
                body.put("contents", List.of(
                        Map.of("role", "user", "parts", List.of(Map.of("text", normalizedMessage)))
                ));
                body.put("generationConfig", Map.of(
                        "temperature", normalizedTemperature,
                        "topP", normalizedTopP,
                        "maxOutputTokens", normalizedMaxTokens
                ));
                String geminiPath = "/models/" + urlEncode(normalizedModelCode) + ":generateContent";
                String geminiUrl = joinUrl(baseUrl, geminiPath) + "?key=" + urlEncode(required(apiKey, "api_key_secret_ref"));
                return new ProviderRequest(geminiUrl, headers, body);
            }
            default -> throw new ResponseStatusException(BAD_REQUEST, "Unsupported provider protocol: " + providerProtocol);
        }
    }

    private ProviderRequest buildProviderProbeRequest(
            LlmProviderConfig provider,
            String providerProtocol,
            String modelCode,
            Map<String, Object> requestBodyOverride
    ) {
        String baseUrl = required(provider.getBaseUrl(), "base_url").replaceAll("/+$", "");
        Map<String, String> headers = buildProviderHeaders(provider);
        String apiKey = provider.getApiKeySecretRef() == null || provider.getApiKeySecretRef().isBlank()
                ? ""
                : resolveSecret(provider.getApiKeySecretRef());
        String normalizedModelCode = required(modelCode, "model_code");

        if (requestBodyOverride != null && !requestBodyOverride.isEmpty()) {
            return new ProviderRequest(
                    probeEndpointFor(providerProtocol, baseUrl, normalizedModelCode, apiKey),
                    probeHeadersFor(providerProtocol, headers, apiKey),
                    normalizeProbeBody(providerProtocol, normalizedModelCode, requestBodyOverride)
            );
        }

        return switch (providerProtocol) {
            case "doubao" -> {
                yield new ProviderRequest(
                        probeEndpointFor(providerProtocol, baseUrl, normalizedModelCode, apiKey),
                        probeHeadersFor(providerProtocol, headers, apiKey),
                        defaultProbeBody(providerProtocol, normalizedModelCode)
                );
            }
            case "openai", "openai_compatible", "deepseek", "qwen", "custom" -> {
                yield new ProviderRequest(
                        probeEndpointFor(providerProtocol, baseUrl, normalizedModelCode, apiKey),
                        probeHeadersFor(providerProtocol, headers, apiKey),
                        defaultProbeBody(providerProtocol, normalizedModelCode)
                );
            }
            case "claude" -> {
                yield new ProviderRequest(
                        probeEndpointFor(providerProtocol, baseUrl, normalizedModelCode, apiKey),
                        probeHeadersFor(providerProtocol, headers, apiKey),
                        defaultProbeBody(providerProtocol, normalizedModelCode)
                );
            }
            case "gemini" -> {
                yield new ProviderRequest(
                        probeEndpointFor(providerProtocol, baseUrl, normalizedModelCode, apiKey),
                        probeHeadersFor(providerProtocol, headers, apiKey),
                        defaultProbeBody(providerProtocol, normalizedModelCode)
                );
            }
            default -> throw new ResponseStatusException(BAD_REQUEST, "Unsupported provider protocol: " + providerProtocol);
        };
    }

    private String defaultModelCodeForProvider(String providerCode) {
        return providerRepository.findByProviderCode(providerCode)
                .map(LlmProviderConfig::getDefaultModelCode)
                .filter(value -> value != null && !value.isBlank())
                .orElse("gpt-4o-mini");
    }

    private String resolveEffectiveModelCode(LlmModelProfile profile) {
        return providerRepository.findByProviderCode(profile.getProviderCode())
                .map(provider -> effectiveModelCode(provider, profile))
                .orElse(profile.getModelCode());
    }

    private String effectiveModelCode(LlmProviderConfig provider, LlmModelProfile profile) {
        return provider.getDefaultModelCode() != null && !provider.getDefaultModelCode().isBlank()
                ? provider.getDefaultModelCode()
                : profile.getModelCode();
    }

    private String probeEndpointFor(String providerProtocol, String baseUrl, String modelCode, String apiKey) {
        return switch (providerProtocol) {
            case "openai", "openai_compatible", "deepseek", "qwen", "custom" -> joinUrl(baseUrl, "/chat/completions");
            case "doubao" -> joinUrl(baseUrl, "/responses");
            case "claude" -> joinUrl(baseUrl, "/messages");
            case "gemini" -> joinUrl(baseUrl, "/models/" + urlEncode(modelCode) + ":generateContent") + "?key=" + urlEncode(required(apiKey, "api_key_secret_ref"));
            default -> throw new ResponseStatusException(BAD_REQUEST, "Unsupported provider protocol: " + providerProtocol);
        };
    }

    private Map<String, String> probeHeadersFor(String providerProtocol, Map<String, String> baseHeaders, String apiKey) {
        Map<String, String> headers = new LinkedHashMap<>(baseHeaders);
        if ("claude".equals(providerProtocol)) {
            headers.remove(HttpHeaders.AUTHORIZATION);
            headers.put("x-api-key", required(apiKey, "api_key_secret_ref"));
            headers.put("anthropic-version", "2023-06-01");
        } else if ("gemini".equals(providerProtocol)) {
            headers.remove(HttpHeaders.AUTHORIZATION);
        }
        return headers;
    }

    private Map<String, Object> defaultProbeBody(String providerProtocol, String modelCode) {
        return switch (providerProtocol) {
            case "openai", "openai_compatible", "deepseek", "qwen", "custom" -> Map.of(
                    "model", modelCode,
                    "messages", List.of(Map.of("role", "user", "content", "请回复：连接测试成功")),
                    "max_tokens", 32
            );
            case "doubao" -> Map.of(
                    "model", modelCode,
                    "input", List.of(Map.of(
                            "role", "user",
                            "content", List.of(
                                    Map.of("type", "input_image", "image_url", "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png"),
                                    Map.of("type", "input_text", "text", "你看见了什么？")
                            )
                    ))
            );
            case "claude" -> Map.of(
                    "model", modelCode,
                    "max_tokens", 32,
                    "messages", List.of(Map.of("role", "user", "content", "请回复：连接测试成功"))
            );
            case "gemini" -> Map.of(
                    "contents", List.of(Map.of(
                            "role", "user",
                            "parts", List.of(Map.of("text", "请回复：连接测试成功"))
                    ))
            );
            default -> throw new ResponseStatusException(BAD_REQUEST, "Unsupported provider protocol: " + providerProtocol);
        };
    }

    private Map<String, Object> normalizeProbeBody(String providerProtocol, String modelCode, Map<String, Object> requestBodyOverride) {
        if (!"doubao".equals(providerProtocol) || requestBodyOverride.get("input") != null) {
            return requestBodyOverride;
        }

        Object messages = requestBodyOverride.get("messages");
        if (!(messages instanceof List<?> messageList) || messageList.isEmpty()) {
            return requestBodyOverride;
        }

        StringBuilder text = new StringBuilder();
        for (Object item : messageList) {
            if (!(item instanceof Map<?, ?> messageMap)) {
                continue;
            }
            Object content = messageMap.get("content");
            if (content != null && !String.valueOf(content).isBlank()) {
                if (!text.isEmpty()) {
                    text.append("\n");
                }
                text.append(String.valueOf(content));
            }
        }
        if (text.isEmpty()) {
            return requestBodyOverride;
        }

        Map<String, Object> normalized = new LinkedHashMap<>(requestBodyOverride);
        normalized.remove("messages");
        normalized.remove("max_tokens");
        normalized.put("model", modelCode);
        normalized.put("input", List.of(
                Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "input_text", "text", text.toString())
                        )
                )
        ));
        return normalized;
    }

    private String extractProviderText(String providerType, Map<String, Object> payload) {
        try {
            switch (providerType) {
                case "openai", "openai_compatible", "deepseek", "qwen", "custom" -> {
                    Object choices = payload.get("choices");
                    if (choices instanceof List<?> list && !list.isEmpty()) {
                        Object first = list.get(0);
                        if (first instanceof Map<?, ?> map) {
                            Object messageMap = map.get("message");
                            if (messageMap instanceof Map<?, ?> messageValue) {
                                Object content = messageValue.get("content");
                                if (content != null) {
                                    return String.valueOf(content);
                                }
                            }
                        }
                    }
                }
                case "doubao" -> {
                    Object outputText = payload.get("output_text");
                    if (outputText != null && !String.valueOf(outputText).isBlank()) {
                        return String.valueOf(outputText);
                    }
                    Object output = payload.get("output");
                    if (output instanceof List<?> list) {
                        for (Object item : list) {
                            if (!(item instanceof Map<?, ?> map)) {
                                continue;
                            }
                            Object content = map.get("content");
                            String contentText = extractTextFromContent(content);
                            if (contentText != null) {
                                return contentText;
                            }
                        }
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
                        if (first instanceof Map<?, ?> firstMap) {
                            Object content = firstMap.get("content");
                            if (content instanceof Map<?, ?> contentMap) {
                                Object parts = contentMap.get("parts");
                                if (parts instanceof List<?> partList && !partList.isEmpty()) {
                                    Object part = partList.get(0);
                                    if (part instanceof Map<?, ?> partMap && partMap.get("text") != null) {
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
        if ("incomplete".equals(String.valueOf(payload.get("status")))) {
            Object details = payload.get("incomplete_details");
            if (details instanceof Map<?, ?> detailMap && detailMap.get("reason") != null) {
                throw new ResponseStatusException(BAD_REQUEST, "Provider returned incomplete chat response: reason=" + detailMap.get("reason") + ", payload=" + payload);
            }
        }
        throw new ResponseStatusException(BAD_REQUEST, "Provider returned invalid chat response: " + payload);
    }

    private String extractTextFromContent(Object content) {
        if (content instanceof String text && !text.isBlank()) {
            return text;
        }
        if (!(content instanceof List<?> contentList)) {
            return null;
        }
        for (Object contentItem : contentList) {
            if (contentItem instanceof Map<?, ?> contentMap) {
                Object text = contentMap.get("text");
                if (text != null && !String.valueOf(text).isBlank()) {
                    return String.valueOf(text);
                }
            }
        }
        return null;
    }

    private String resolveProviderProtocol(String providerType) {
        return "custom".equals(providerType) ? "openai" : providerType;
    }

    private String joinUrl(String baseUrl, String path) {
        String normalizedBaseUrl = baseUrl.replaceAll("/+$", "");
        if (path == null || path.isBlank()) {
            return normalizedBaseUrl;
        }
        String normalizedPath = path.trim();
        if (normalizedPath.startsWith("http://") || normalizedPath.startsWith("https://")) {
            return normalizedPath;
        }
        if (!normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }
        if (normalizedBaseUrl.endsWith(normalizedPath)) {
            return normalizedBaseUrl;
        }
        return normalizedBaseUrl + normalizedPath;
    }

    private String normalizeProviderType(String providerType) {
        return providerType == null ? "" : providerType.trim().toLowerCase(Locale.ROOT);
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String resolveSecret(String secretRef) {
        if (secretRef.startsWith("env:")) {
            String envName = secretRef.substring("env:".length());
            String value = System.getenv(envName);
            if (value == null || value.isBlank()) {
                throw new ResponseStatusException(BAD_REQUEST, "Missing environment secret: " + envName);
            }
            return value;
        }
        return secretRef;
    }

    private void requireAdmin(String userId, String action) {
        accessControlService.requireWorkflowAdminAction(userId, 1L, "model_config", action);
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, field + " is required");
        }
        return value.trim();
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String firstNonBlank(String primary, String fallback) {
        String normalizedPrimary = blankToNull(primary);
        return normalizedPrimary != null ? normalizedPrimary : blankToNull(fallback);
    }

    @SafeVarargs
    private <T> T coalesce(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof Map<?, ?> map && map.isEmpty()) {
                return null;
            }
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid JSON payload");
        }
    }

    private String secretMode(String secretRef) {
        if (secretRef == null || secretRef.isBlank()) {
            return "none";
        }
        return secretRef.startsWith("env:") ? "env" : "direct";
    }

    private boolean isSecretAvailable(String secretRef) {
        if (secretRef == null || secretRef.isBlank()) {
            return false;
        }
        if (!secretRef.startsWith("env:")) {
            return true;
        }
        String envName = secretRef.substring("env:".length());
        String value = System.getenv(envName);
        return value != null && !value.isBlank();
    }

    private String secretMissingReason(String secretRef) {
        if (secretRef == null || secretRef.isBlank() || !secretRef.startsWith("env:")) {
            return null;
        }
        String envName = secretRef.substring("env:".length());
        String value = System.getenv(envName);
        if (value != null && !value.isBlank()) {
            return null;
        }
        return "Missing environment secret: " + envName;
    }

    private String maskSecret(String secretRef) {
        if (secretRef == null || secretRef.isBlank()) {
            return "";
        }
        if (secretRef.startsWith("env:")) {
            return secretRef;
        }
        String compact = secretRef.trim();
        if (compact.length() <= 8) {
            return "****";
        }
        return compact.substring(0, 4) + "****" + compact.substring(compact.length() - 4);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String normalizeUserId(String userId) {
        return userId == null || userId.isBlank() ? "anonymous" : userId;
    }

    private String defaultString(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static final class TypeReferenceReference extends org.springframework.core.ParameterizedTypeReference<Map<String, Object>> {
    }

    private record ProviderRequest(
            String url,
            Map<String, String> headers,
            Map<String, Object> body
    ) {
    }

    public record RuntimeModelBundle(
            List<Map<String, Object>> providerConfigs,
            List<Map<String, Object>> modelProfiles
    ) {
    }

    private record ProfilePreset(
            BigDecimal temperature,
            BigDecimal topP,
            Integer maxTokens,
            Integer timeoutSec
    ) {
    }

    private ProfilePreset profilePresetForPurpose(String purpose) {
        String normalizedPurpose = normalizePurpose(purpose);
        return switch (normalizedPurpose) {
            case "intent_routing" -> new ProfilePreset(BigDecimal.valueOf(0.10d), BigDecimal.valueOf(0.80d), 512, 15);
            case "knowledge_query_rewrite" -> new ProfilePreset(BigDecimal.valueOf(0.10d), BigDecimal.valueOf(0.90d), 512, 15);
            case "knowledge_answer" -> new ProfilePreset(BigDecimal.valueOf(0.20d), BigDecimal.valueOf(0.90d), 1024, 15);
            case "general_llm" -> new ProfilePreset(BigDecimal.valueOf(0.30d), BigDecimal.valueOf(0.95d), 1024, 15);
            case "structured_extraction" -> new ProfilePreset(BigDecimal.valueOf(0.10d), BigDecimal.valueOf(0.80d), 512, 15);
            default -> new ProfilePreset(null, null, null, null);
        };
    }

    private String normalizePurpose(String purpose) {
        return purpose == null ? "" : purpose.trim().toLowerCase(Locale.ROOT);
    }
}
