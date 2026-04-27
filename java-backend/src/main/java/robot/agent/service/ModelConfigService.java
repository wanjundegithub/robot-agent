package robot.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import robot.agent.dto.request.TestModelRecordRequest;
import robot.agent.dto.request.UpsertModelProviderRequest;
import robot.agent.dto.request.UpsertModelRecordRequest;
import robot.agent.dto.request.ValidateModelProviderRequest;
import robot.agent.model.CapabilityAuthConfig;
import robot.agent.model.CapabilityGroupSnapshot;
import robot.agent.model.CapabilityItem;
import robot.agent.model.LlmModelRecord;
import robot.agent.model.LlmProviderConfig;
import robot.agent.model.WorkflowVersion;
import robot.agent.repository.CapabilityAuthConfigRepository;
import robot.agent.repository.CapabilityGroupSnapshotRepository;
import robot.agent.repository.CapabilityItemRepository;
import robot.agent.repository.LlmModelRecordRepository;
import robot.agent.repository.LlmProviderConfigRepository;
import robot.agent.repository.WorkflowVersionRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ModelConfigService {

    private static final String DEFAULT_ROUTING_MODEL_CODE = "intent-router-v1";

    private final LlmProviderConfigRepository providerRepository;
    private final LlmModelRecordRepository modelRecordRepository;
    private final WorkflowVersionRepository workflowVersionRepository;
    private final CapabilityItemRepository capabilityItemRepository;
    private final CapabilityGroupSnapshotRepository capabilityGroupSnapshotRepository;
    private final CapabilityAuthConfigRepository capabilityAuthConfigRepository;
    private final ObjectMapper objectMapper;
    private final AccessControlService accessControlService;
    private final AuditService auditService;
    private final UnifiedModelService unifiedModelService;

    @Autowired
    public ModelConfigService(
            LlmProviderConfigRepository providerRepository,
            LlmModelRecordRepository modelRecordRepository,
            WorkflowVersionRepository workflowVersionRepository,
            CapabilityItemRepository capabilityItemRepository,
            CapabilityGroupSnapshotRepository capabilityGroupSnapshotRepository,
            CapabilityAuthConfigRepository capabilityAuthConfigRepository,
            ObjectMapper objectMapper,
            AccessControlService accessControlService,
            AuditService auditService,
            UnifiedModelService unifiedModelService
    ) {
        this.providerRepository = providerRepository;
        this.modelRecordRepository = modelRecordRepository;
        this.workflowVersionRepository = workflowVersionRepository;
        this.capabilityItemRepository = capabilityItemRepository;
        this.capabilityGroupSnapshotRepository = capabilityGroupSnapshotRepository;
        this.capabilityAuthConfigRepository = capabilityAuthConfigRepository;
        this.objectMapper = objectMapper;
        this.accessControlService = accessControlService;
        this.auditService = auditService;
        this.unifiedModelService = unifiedModelService;
    }

    public ModelConfigService(
            LlmProviderConfigRepository providerRepository,
            LlmModelRecordRepository modelRecordRepository,
            WorkflowVersionRepository workflowVersionRepository,
            CapabilityItemRepository capabilityItemRepository,
            CapabilityGroupSnapshotRepository capabilityGroupSnapshotRepository,
            CapabilityAuthConfigRepository capabilityAuthConfigRepository,
            ObjectMapper objectMapper,
            AccessControlService accessControlService,
            AuditService auditService
    ) {
        this(
                providerRepository,
                modelRecordRepository,
                workflowVersionRepository,
                capabilityItemRepository,
                capabilityGroupSnapshotRepository,
                capabilityAuthConfigRepository,
                objectMapper,
                accessControlService,
                auditService,
                new UnifiedModelService(modelRecordRepository, providerRepository, objectMapper)
        );
    }

    public RuntimeModelBundle buildRuntimeBundle(Collection<Map<String, Object>> workflowDefinitions, String routingModelCode) {
        Set<String> modelCodes = new LinkedHashSet<>();
        if (routingModelCode != null && !routingModelCode.isBlank()) {
            modelCodes.add(routingModelCode);
        }
        for (Map<String, Object> workflowDefinition : workflowDefinitions) {
            collectModelCodes(workflowDefinition, modelCodes);
        }

        List<LlmModelRecord> modelRecords = modelCodes.isEmpty()
                ? List.of()
                : modelRecordRepository.findByModelCodeIn(modelCodes);
        Map<String, LlmProviderConfig> providersByCode = new LinkedHashMap<>();
        for (LlmModelRecord modelRecord : modelRecords) {
            providerRepository.findByProviderCode(modelRecord.getProviderCode())
                    .ifPresent(provider -> providersByCode.put(provider.getProviderCode(), provider));
        }

        List<Map<String, Object>> providerConfigs = providersByCode.values().stream()
                .sorted(Comparator.comparing(LlmProviderConfig::getProviderCode, String.CASE_INSENSITIVE_ORDER))
                .map(this::providerToRuntimeMap)
                .toList();
        List<Map<String, Object>> modelRecordMaps = modelRecords.stream()
                .sorted(Comparator.comparing(LlmModelRecord::getModelCode, String.CASE_INSENSITIVE_ORDER))
                .map(modelRecord -> modelRecordToRuntimeMap(modelRecord, providersByCode.get(modelRecord.getProviderCode())))
                .toList();
        return new RuntimeModelBundle(providerConfigs, modelRecordMaps);
    }

    public String resolveRoutingModelCode(Collection<Map<String, Object>> workflowDefinitions) {
        for (Map<String, Object> workflowDefinition : workflowDefinitions) {
            String modelCode = findRoutingModelCode(workflowDefinition);
            if (modelCode != null && !modelCode.isBlank()) {
                return modelCode;
            }
        }
        return DEFAULT_ROUTING_MODEL_CODE;
    }

    public List<Map<String, Object>> getProviderConfigs() {
        return providerRepository.findAll().stream()
                .sorted(Comparator.comparing(LlmProviderConfig::getProviderCode, String.CASE_INSENSITIVE_ORDER))
                .map(this::providerToResponseMap)
                .toList();
    }

    public Map<String, Object> getModelRecords(String keyword, String providerCode, Boolean enabled, int page, int pageSize) {
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                Math.max(pageSize, 1),
                Sort.by(Sort.Order.desc("updatedAt"))
        );
        String normalizedKeyword = blankToNull(keyword);
        String normalizedProviderCode = blankToNull(providerCode);
        Page<LlmModelRecord> modelPage = modelRecordRepository.search(normalizedKeyword, normalizedProviderCode, enabled, pageable);
        Map<String, LlmProviderConfig> providersByCode = loadProvidersByCode(modelPage.getContent());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("page", modelPage.getNumber());
        envelope.put("page_size", modelPage.getSize());
        envelope.put("total", modelPage.getTotalElements());
        envelope.put("items", modelPage.getContent().stream()
                .map(modelRecord -> modelRecordToAdminResponseMap(modelRecord, providersByCode.get(modelRecord.getProviderCode())))
                .toList());
        return envelope;
    }

    public Map<String, Object> getModelRecord(Long id) {
        LlmModelRecord modelRecord = modelRecordRepository.findById(id)
                .orElseThrow(() -> badRequest("Model record not found: " + id));
        return modelRecordToAdminResponseMap(modelRecord, findProviderSnapshot(modelRecord));
    }

    @Transactional
    public Map<String, Object> saveProviderConfig(String userId, UpsertModelProviderRequest request) {
        requireAdmin(userId, "model.provider.create");
        String providerCode = required(request.getProviderCode(), "provider_code");
        if (providerRepository.findByProviderCode(providerCode).isPresent()) {
            throw badRequest("Provider already exists: " + providerCode);
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
                .orElseThrow(() -> badRequest("Provider not found: " + providerCode));
        applyProviderRequest(provider, request, false);
        provider.setUpdatedAt(LocalDateTime.now());
        LlmProviderConfig saved = providerRepository.save(provider);
        auditService.logAction(1L, normalizeUserId(userId), "model.provider.update", "llm_provider_config", providerCode, null, 200);
        return providerToResponseMap(saved);
    }

    @Transactional
    public void deleteProviderConfig(String userId, String providerCode) {
        requireAdmin(userId, "model.provider.delete");
        LlmProviderConfig provider = providerRepository.findByProviderCode(providerCode)
                .orElseThrow(() -> badRequest("Provider not found: " + providerCode));
        long references = modelRecordRepository.countByProviderCode(providerCode);
        if (references > 0L) {
            throw badRequest("provider is still referenced by model records");
        }
        providerRepository.delete(provider);
        auditService.logAction(1L, normalizeUserId(userId), "model.provider.delete", "llm_provider_config", providerCode, null, 200);
    }

    public Map<String, Object> validateProviderConfig(String userId, String providerCode, ValidateModelProviderRequest request) {
        requireAdmin(userId, "model.provider.validate");
        LlmProviderConfig provider = providerRepository.findByProviderCode(providerCode)
                .orElseThrow(() -> badRequest("Provider not found: " + providerCode));
        String modelCode = resolveProviderValidationModelCode(providerCode, request);
        int statusCode = unifiedModelService.validateProviderConnection(provider, modelCode, request.getRequestBody());
        return validationResponse(true, providerCode, modelCode, statusCode);
    }

    public Map<String, Object> validateProviderDraft(String userId, ValidateModelProviderRequest request) {
        requireAdmin(userId, "model.provider.validate");
        LlmProviderConfig provider = new LlmProviderConfig();
        provider.setProviderType(required(request.getProviderType(), "provider_type"));
        provider.setBaseUrl(required(request.getBaseUrl(), "base_url").replaceAll("/+$", ""));
        provider.setApiKeySecretRef(blankToNull(request.getApiKeySecretRef()));
        provider.setEnabled(true);
        String modelCode = firstNonBlank(blankToNull(request.getModelCode()), "gpt-4o-mini");
        int statusCode = unifiedModelService.validateProviderConnection(provider, modelCode, request.getRequestBody());
        return validationResponse(true, "draft-provider", modelCode, statusCode);
    }

    @Transactional
    public Map<String, Object> saveModelRecord(String userId, UpsertModelRecordRequest request) {
        requireAdmin(userId, "model.record.create");
        LlmModelRecord modelRecord = new LlmModelRecord();
        String modelCode = generateHiddenModelCode();
        modelRecord.setModelCode(modelCode);
        modelRecord.setProviderCode(providerCodeFor(modelCode));
        applyModelRecordRequest(modelRecord, request, true);
        modelRecord.setCreatedBy(normalizeUserId(userId));
        modelRecord.setCreatedAt(LocalDateTime.now());
        modelRecord.setUpdatedAt(LocalDateTime.now());
        LlmModelRecord saved = modelRecordRepository.save(modelRecord);
        upsertInternalProvider(saved, userId);
        auditService.logAction(1L, normalizeUserId(userId), "model.record.create", "llm_model_record", modelCode, null, 200);
        return modelRecordToAdminResponseMap(saved, findProviderSnapshot(saved));
    }

    @Transactional
    public Map<String, Object> updateModelRecord(String userId, Long id, UpsertModelRecordRequest request) {
        requireAdmin(userId, "model.record.update");
        LlmModelRecord modelRecord = modelRecordRepository.findById(id)
                .orElseThrow(() -> badRequest("Model record not found: " + id));
        applyModelRecordRequest(modelRecord, request, false);
        modelRecord.setUpdatedAt(LocalDateTime.now());
        LlmModelRecord saved = modelRecordRepository.save(modelRecord);
        upsertInternalProvider(saved, userId);
        auditService.logAction(1L, normalizeUserId(userId), "model.record.update", "llm_model_record", saved.getModelCode(), null, 200);
        return modelRecordToAdminResponseMap(saved, findProviderSnapshot(saved));
    }

    @Transactional
    public void deleteModelRecord(String userId, Long id) {
        requireAdmin(userId, "model.record.delete");
        LlmModelRecord modelRecord = modelRecordRepository.findById(id)
                .orElseThrow(() -> badRequest("Model record not found: " + id));
        List<String> references = collectModelRecordReferences(modelRecord.getModelCode());
        if (!references.isEmpty()) {
            throw badRequest("model record is still referenced: " + String.join(", ", references));
        }
        providerRepository.findByProviderCode(modelRecord.getProviderCode()).ifPresent(providerRepository::delete);
        modelRecordRepository.delete(modelRecord);
        auditService.logAction(1L, normalizeUserId(userId), "model.record.delete", "llm_model_record", modelRecord.getModelCode(), null, 200);
    }

    public Map<String, Object> validateModelRecord(String userId, String modelCode) {
        requireAdmin(userId, "model.record.validate");
        LlmModelRecord modelRecord = modelRecordRepository.findByModelCode(modelCode)
                .orElseThrow(() -> badRequest("Model record not found: " + modelCode));
        LlmProviderConfig provider = requireProvider(modelRecord.getProviderCode());
        int statusCode = unifiedModelService.validateProviderConnection(
                provider,
                firstNonBlank(modelRecord.getUpstreamModelCode(), modelRecord.getModelCode()),
                Map.of()
        );
        return validationResponse(true, provider.getProviderCode(), modelRecord.getModelCode(), statusCode);
    }

    public Map<String, Object> testModelRecordChat(String userId, String modelCode, TestModelRecordRequest request) {
        requireAdmin(userId, "model.record.test_chat");
        List<Map<String, Object>> messages = request.getMessages();
        if (messages == null || messages.isEmpty()) {
            messages = List.of(Map.of("role", "user", "content", firstNonBlank(request.getMessage(), "ping")));
        }
        UnifiedModelResult result = unifiedModelService.invokeChat(new UnifiedModelRequest(
                modelCode,
                messages,
                blankToNull(request.getSystemPrompt()),
                request.getOptions() == null ? Map.of() : request.getOptions(),
                null,
                false
        ));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("model_code", result.modelCode());
        response.put("provider_code", result.providerCode());
        response.put("upstream_model_code", result.upstreamModelCode());
        response.put("answer", result.text());
        response.put("usage", result.usage());
        return response;
    }

    public Map<String, Object> testSimpleModelConnection(String userId, UpsertModelRecordRequest request) {
        requireAdmin(userId, "model.record.test_chat");
        UnifiedModelResult result = unifiedModelService.invokeDirectChat(
                required(firstNonBlank(request.getProvider(), request.getProviderCode()), "provider"),
                required(request.getBaseUrl(), "base_url").replaceAll("/+$", ""),
                required(request.getApiKey(), "api_key"),
                required(request.getModelName(), "model_name"),
                List.of(Map.of("role", "user", "content", "ping")),
                Map.of()
        );
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("provider", request.getProvider());
        response.put("model_name", request.getModelName());
        response.put("answer", result.text());
        response.put("usage", result.usage());
        return response;
    }

    private Map<String, LlmProviderConfig> loadProvidersByCode(List<LlmModelRecord> modelRecords) {
        Set<String> providerCodes = new LinkedHashSet<>();
        for (LlmModelRecord modelRecord : modelRecords) {
            if (modelRecord.getProviderCode() != null && !modelRecord.getProviderCode().isBlank()) {
                providerCodes.add(modelRecord.getProviderCode());
            }
        }
        Map<String, LlmProviderConfig> providersByCode = new LinkedHashMap<>();
        if (!providerCodes.isEmpty()) {
            for (LlmProviderConfig provider : providerRepository.findByProviderCodeIn(providerCodes)) {
                providersByCode.put(provider.getProviderCode(), provider);
            }
        }
        return providersByCode;
    }

    private void collectModelCodes(Object source, Set<String> refs) {
        if (source instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    String key = String.valueOf(entry.getKey());
                    if (("model_code".equals(key) || "routing_model_code".equals(key))
                            && entry.getValue() != null
                            && !String.valueOf(entry.getValue()).isBlank()) {
                        refs.add(String.valueOf(entry.getValue()));
                    }
                }
                collectModelCodes(entry.getValue(), refs);
            }
            return;
        }
        if (source instanceof List<?> list) {
            for (Object item : list) {
                collectModelCodes(item, refs);
            }
        }
    }

    private String findRoutingModelCode(Object source) {
        if (source instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    String key = String.valueOf(entry.getKey());
                    if ("routing_model_code".equals(key)
                            && entry.getValue() != null
                            && !String.valueOf(entry.getValue()).isBlank()) {
                        return String.valueOf(entry.getValue());
                    }
                }
                String nested = findRoutingModelCode(entry.getValue());
                if (nested != null && !nested.isBlank()) {
                    return nested;
                }
            }
            return null;
        }
        if (source instanceof List<?> list) {
            for (Object item : list) {
                String nested = findRoutingModelCode(item);
                if (nested != null && !nested.isBlank()) {
                    return nested;
                }
            }
        }
        return null;
    }

    private String resolveProviderValidationModelCode(String providerCode, ValidateModelProviderRequest request) {
        String explicit = blankToNull(request.getModelCode());
        if (explicit != null) {
            return explicit;
        }
        return modelRecordRepository.findByProviderCodeOrderByUpdatedAtDesc(providerCode).stream()
                .map(modelRecord -> firstNonBlank(modelRecord.getUpstreamModelCode(), modelRecord.getModelCode()))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("gpt-4o-mini");
    }

    private void applyProviderRequest(LlmProviderConfig provider, UpsertModelProviderRequest request, boolean creating) {
        if (creating) {
            provider.setProviderCode(required(request.getProviderCode(), "provider_code"));
        }
        provider.setProviderName(required(request.getProviderName(), "provider_name"));
        provider.setProviderType(required(request.getProviderType(), "provider_type"));
        provider.setBaseUrl(required(request.getBaseUrl(), "base_url").replaceAll("/+$", ""));
        provider.setApiKeySecretRef(blankToNull(request.getApiKeySecretRef()));
        if (request.getEnabled() != null) {
            provider.setEnabled(request.getEnabled());
        }
    }

    private void applyModelRecordRequest(LlmModelRecord modelRecord, UpsertModelRecordRequest request, boolean creating) {
        LlmProviderConfig existingProvider = findProviderSnapshot(modelRecord);
        String customModelName = required(firstNonBlank(
                blankToNull(request.getCustomModelName()),
                blankToNull(modelRecord.getModelName())
        ), "custom_model_name");
        String provider = required(firstNonBlank(
                blankToNull(request.getProvider()),
                blankToNull(modelRecord.getProvider()),
                existingProvider == null ? null : blankToNull(existingProvider.getProviderType())
        ), "provider");
        String actualModelName = required(firstNonBlank(
                blankToNull(request.getModelName()),
                blankToNull(request.getUpstreamModelCode()),
                blankToNull(modelRecord.getUpstreamModelCode()),
                blankToNull(modelRecord.getModelCode())
        ), "model_name");
        String apiKey = required(firstNonBlank(
                blankToNull(request.getApiKey()),
                blankToNull(modelRecord.getApiKey()),
                existingProvider == null ? null : blankToNull(existingProvider.getApiKeySecretRef())
        ), "api_key");
        String baseUrl = required(firstNonBlank(
                blankToNull(request.getBaseUrl()),
                blankToNull(modelRecord.getBaseUrl()),
                existingProvider == null ? null : blankToNull(existingProvider.getBaseUrl())
        ), "base_url").replaceAll("/+$", "");

        if (creating && (modelRecord.getProviderCode() == null || modelRecord.getProviderCode().isBlank())) {
            modelRecord.setProviderCode(providerCodeFor(required(modelRecord.getModelCode(), "model_code")));
        }
        modelRecord.setModelName(customModelName);
        modelRecord.setProvider(provider);
        modelRecord.setProviderType(provider);
        modelRecord.setUpstreamModelCode(actualModelName);
        modelRecord.setApiKey(apiKey);
        modelRecord.setBaseUrl(baseUrl);
        modelRecord.setCapabilitiesJson(null);
        modelRecord.setDefaultSystemPrompt(null);
        modelRecord.setDefaultOptionsJson(null);
        modelRecord.setEnabled(true);
    }

    private LlmProviderConfig requireProvider(String providerCode) {
        return providerRepository.findByProviderCode(providerCode)
                .orElseThrow(() -> badRequest("Provider not found: " + providerCode));
    }

    private Map<String, Object> providerToRuntimeMap(LlmProviderConfig provider) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("provider_code", provider.getProviderCode());
        value.put("provider_name", provider.getProviderName());
        value.put("provider_type", provider.getProviderType());
        value.put("base_url", provider.getBaseUrl());
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
        String apiKeyError = secretMissingReason(provider.getApiKeySecretRef());
        if (apiKeyError != null) {
            value.put("api_key_error", apiKeyError);
        }
        value.put("created_at", provider.getCreatedAt());
        value.put("updated_at", provider.getUpdatedAt());
        return value;
    }

    private Map<String, Object> modelRecordToRuntimeMap(LlmModelRecord modelRecord, LlmProviderConfig provider) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("model_code", modelRecord.getModelCode());
        value.put("model_name", modelRecord.getModelName());
        value.put("provider_code", modelRecord.getProviderCode());
        value.put("provider_type", provider == null ? modelRecord.getProviderType() : provider.getProviderType());
        value.put("upstream_model_code", firstNonBlank(modelRecord.getUpstreamModelCode(), modelRecord.getModelCode()));
        value.put("capabilities", parseJson(modelRecord.getCapabilitiesJson()));
        value.put("default_system_prompt", modelRecord.getDefaultSystemPrompt());
        value.put("default_options", parseJson(modelRecord.getDefaultOptionsJson()));
        value.put("enabled", modelRecord.isEnabled());
        return value;
    }

    private Map<String, Object> modelRecordToAdminResponseMap(LlmModelRecord modelRecord, LlmProviderConfig provider) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", modelRecord.getId());
        value.put("custom_model_name", modelRecord.getModelName());
        value.put("provider", firstNonBlank(
                blankToNull(modelRecord.getProvider()),
                provider == null ? blankToNull(modelRecord.getProviderType()) : blankToNull(provider.getProviderType())
        ));
        value.put("model_name", firstNonBlank(modelRecord.getUpstreamModelCode(), modelRecord.getModelCode()));
        value.put("api_key", firstNonBlank(
                blankToNull(modelRecord.getApiKey()),
                provider == null ? null : blankToNull(provider.getApiKeySecretRef())
        ));
        value.put("base_url", firstNonBlank(
                blankToNull(modelRecord.getBaseUrl()),
                provider == null ? null : blankToNull(provider.getBaseUrl())
        ));
        value.put("created_at", modelRecord.getCreatedAt());
        value.put("updated_at", modelRecord.getUpdatedAt());
        return value;
    }

    private Map<String, Object> validationResponse(boolean valid, String providerCode, String modelCode, int statusCode) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("valid", valid);
        response.put("provider_code", providerCode);
        response.put("tested_model_code", modelCode);
        response.put("status_code", statusCode);
        response.put("message", "Provider validation succeeded");
        return response;
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

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof Map<?, ?> map && map.isEmpty()) {
                return null;
            }
            if (value instanceof Collection<?> collection && collection.isEmpty()) {
                return null;
            }
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw badRequest("Invalid JSON payload");
        }
    }

    private LlmProviderConfig findProviderSnapshot(LlmModelRecord modelRecord) {
        if (modelRecord == null || modelRecord.getProviderCode() == null || modelRecord.getProviderCode().isBlank()) {
            return null;
        }
        return providerRepository.findByProviderCode(modelRecord.getProviderCode()).orElse(null);
    }

    private void upsertInternalProvider(LlmModelRecord modelRecord, String userId) {
        String providerCode = required(firstNonBlank(modelRecord.getProviderCode(), providerCodeFor(modelRecord.getModelCode())), "provider_code");
        LlmProviderConfig provider = providerRepository.findByProviderCode(providerCode).orElseGet(LlmProviderConfig::new);
        boolean creating = provider.getId() == null;
        provider.setProviderCode(providerCode);
        provider.setProviderName(firstNonBlank(modelRecord.getModelName(), providerCode));
        provider.setProviderType(required(firstNonBlank(modelRecord.getProvider(), modelRecord.getProviderType()), "provider"));
        provider.setBaseUrl(required(modelRecord.getBaseUrl(), "base_url").replaceAll("/+$", ""));
        provider.setApiKeySecretRef(required(modelRecord.getApiKey(), "api_key"));
        provider.setEnabled(true);
        if (creating) {
            provider.setCreatedBy(normalizeUserId(userId));
            provider.setCreatedAt(LocalDateTime.now());
        }
        provider.setUpdatedAt(LocalDateTime.now());
        providerRepository.save(provider);
    }

    private String generateHiddenModelCode() {
        return "model-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String providerCodeFor(String modelCode) {
        return required(modelCode, "model_code") + "-provider";
    }

    private List<String> collectModelRecordReferences(String modelCode) {
        Set<String> references = new LinkedHashSet<>();

        for (WorkflowVersion workflowVersion : workflowVersionRepository.findAll()) {
            if (jsonContainsModelReference(workflowVersion.getDefinition(), modelCode)) {
                references.add("workflow_definition:" + workflowVersion.getWorkflowCode() + "@" + workflowVersion.getVersion());
            }
            if (jsonContainsModelReference(workflowVersion.getConfig(), modelCode)) {
                references.add("workflow_config:" + workflowVersion.getWorkflowCode() + "@" + workflowVersion.getVersion());
            }
        }
        for (CapabilityItem capabilityItem : capabilityItemRepository.findAll()) {
            if (jsonContainsModelReference(capabilityItem.getDefinitionJson(), modelCode)) {
                references.add("capability_definition:" + capabilityItem.getGroupCode() + "/" + capabilityItem.getCapabilityCode());
            }
        }
        for (CapabilityGroupSnapshot snapshot : capabilityGroupSnapshotRepository.findAll()) {
            if (jsonContainsModelReference(snapshot.getSnapshotPayload(), modelCode)) {
                references.add("capability_snapshot:" + snapshot.getGroupCode() + "@" + snapshot.getSnapshotVersion());
            }
        }
        for (CapabilityAuthConfig authConfig : capabilityAuthConfigRepository.findAll()) {
            if (jsonContainsModelReference(authConfig.getConfigJson(), modelCode)) {
                references.add("capability_auth_config:" + authConfig.getGroupCode());
            }
        }
        return new ArrayList<>(references);
    }

    private boolean jsonContainsModelReference(String json, String modelCode) {
        if (json == null || json.isBlank() || modelCode == null || modelCode.isBlank()) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.isTextual()) {
                node = objectMapper.readTree(node.asText());
            }
            return nodeContainsModelReference(node, null, modelCode);
        } catch (Exception exception) {
            return false;
        }
    }

    private boolean nodeContainsModelReference(JsonNode node, String currentKey, String modelCode) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isObject()) {
            java.util.Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isModelReferenceKey(field.getKey())
                        && field.getValue().isValueNode()
                        && modelCode.equals(field.getValue().asText())) {
                    return true;
                }
                if (nodeContainsModelReference(field.getValue(), field.getKey(), modelCode)) {
                    return true;
                }
            }
            return false;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (nodeContainsModelReference(item, currentKey, modelCode)) {
                    return true;
                }
            }
            return false;
        }
        return isModelReferenceKey(currentKey) && modelCode.equals(node.asText());
    }

    private boolean isModelReferenceKey(String key) {
        return "model_code".equals(key) || "routing_model_code".equals(key);
    }

    private void requireAdmin(String userId, String action) {
        accessControlService.requireWorkflowAdminAction(userId, 1L, "model_config", action);
    }

    private ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw badRequest(field + " is required");
        }
        return value.trim();
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
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

    private String normalizeUserId(String userId) {
        return userId == null || userId.isBlank() ? "anonymous" : userId;
    }

    public record RuntimeModelBundle(
            List<Map<String, Object>> providerConfigs,
            List<Map<String, Object>> modelRecords
    ) {
    }
}
