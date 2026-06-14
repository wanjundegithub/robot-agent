package robot.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import robot.agent.common.ApplicationConstants;
import robot.agent.config.DefaultModelProperties;
import robot.agent.config.KnowledgeProperties;
import robot.agent.dto.request.TestModelRecordRequest;
import robot.agent.dto.request.UpsertModelProviderRequest;
import robot.agent.dto.request.UpsertModelRecordRequest;
import robot.agent.dto.request.ValidateModelProviderRequest;
import robot.agent.apicenter.model.ApiItem;
import robot.agent.apicenter.repository.ApiItemRepository;
import robot.agent.model.LlmModelRecord;
import robot.agent.model.LlmProviderConfig;
import robot.agent.model.WorkflowVersion;
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

    private static final Logger log = LoggerFactory.getLogger(ModelConfigService.class);

    private final LlmProviderConfigRepository providerRepository;
    private final LlmModelRecordRepository modelRecordRepository;
    private final WorkflowVersionRepository workflowVersionRepository;
    private final ApiItemRepository apiItemRepository;
    private final ObjectMapper objectMapper;
    private final AccessControlService accessControlService;
    private final AuditService auditService;
    private final UnifiedModelService unifiedModelService;
    private final DefaultModelProperties defaultModelProperties;
    private final KnowledgeProperties knowledgeProperties;

    @Autowired
    public ModelConfigService(
            LlmProviderConfigRepository providerRepository,
            LlmModelRecordRepository modelRecordRepository,
            WorkflowVersionRepository workflowVersionRepository,
            ApiItemRepository apiItemRepository,
            ObjectMapper objectMapper,
            AccessControlService accessControlService,
            AuditService auditService,
            UnifiedModelService unifiedModelService,
            DefaultModelProperties defaultModelProperties,
            KnowledgeProperties knowledgeProperties
    ) {
        this.providerRepository = providerRepository;
        this.modelRecordRepository = modelRecordRepository;
        this.workflowVersionRepository = workflowVersionRepository;
        this.apiItemRepository = apiItemRepository;
        this.objectMapper = objectMapper;
        this.accessControlService = accessControlService;
        this.auditService = auditService;
        this.unifiedModelService = unifiedModelService;
        this.defaultModelProperties = defaultModelProperties;
        this.knowledgeProperties = knowledgeProperties;
    }

    public ModelConfigService(
            LlmProviderConfigRepository providerRepository,
            LlmModelRecordRepository modelRecordRepository,
            WorkflowVersionRepository workflowVersionRepository,
            ApiItemRepository apiItemRepository,
            ObjectMapper objectMapper,
            AccessControlService accessControlService,
            AuditService auditService,
            UnifiedModelService unifiedModelService,
            DefaultModelProperties defaultModelProperties
    ) {
        this(
                providerRepository,
                modelRecordRepository,
                workflowVersionRepository,
                apiItemRepository,
                objectMapper,
                accessControlService,
                auditService,
                unifiedModelService,
                defaultModelProperties,
                new KnowledgeProperties()
        );
    }

    public ModelConfigService(
            LlmProviderConfigRepository providerRepository,
            LlmModelRecordRepository modelRecordRepository,
            WorkflowVersionRepository workflowVersionRepository,
            ApiItemRepository apiItemRepository,
            ObjectMapper objectMapper,
            AccessControlService accessControlService,
            AuditService auditService
    ) {
        this(
                providerRepository,
                modelRecordRepository,
                workflowVersionRepository,
                apiItemRepository,
                objectMapper,
                accessControlService,
                auditService,
                new UnifiedModelService(modelRecordRepository, providerRepository, objectMapper),
                new DefaultModelProperties(),
                new KnowledgeProperties()
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
        log.info(
                "model.runtime.collect routingModelCode={} requestedModelCodes={} loadedModelCount={}",
                routingModelCode,
                modelCodes,
                modelRecords.size()
        );
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
        log.info(
                "model.runtime.bundle.ready providerCount={} modelRecordCount={} providerCodes={}",
                providerConfigs.size(),
                modelRecordMaps.size(),
                providersByCode.keySet()
        );
        return new RuntimeModelBundle(providerConfigs, modelRecordMaps);
    }

    public RuntimeModelBundle buildDefaultRuntimeBundle() {
        String configuredModelCode = defaultModelProperties.resolveModelCode("default");
        RuntimeModelBundle configuredBundle = runtimeBundleForConfiguredDefaultModel(configuredModelCode);
        if (!configuredBundle.modelRecords().isEmpty()) {
            return configuredBundle;
        }

        List<LlmProviderConfig> enabledProviders = providerRepository.findByEnabledTrueOrderByProviderCodeAsc();
        for (LlmProviderConfig provider : enabledProviders) {
            String defaultModelCode = blankToNull(provider.getDefaultModelCode());
            if (defaultModelCode == null) {
                continue;
            }
            LlmModelRecord modelRecord = modelRecordRepository.findByModelCode(defaultModelCode)
                    .filter(LlmModelRecord::isEnabled)
                    .orElse(null);
            if (modelRecord != null) {
                return runtimeBundleForDefaultModel(provider, modelRecord);
            }
        }

        List<LlmModelRecord> enabledModels = modelRecordRepository.search(null, null, true, PageRequest.of(0, 1)).getContent();
        if (enabledModels.isEmpty()) {
            log.info("model.runtime.default.missing reason=no_enabled_models");
            return new RuntimeModelBundle(List.of(), List.of());
        }
        LlmModelRecord modelRecord = enabledModels.get(0);
        LlmProviderConfig provider = providerRepository.findByProviderCode(modelRecord.getProviderCode()).orElse(null);
        if (provider == null || !provider.isEnabled()) {
            log.info(
                    "model.runtime.default.missing reason=provider_unavailable modelCode={} providerCode={}",
                    modelRecord.getModelCode(),
                    modelRecord.getProviderCode()
            );
            return new RuntimeModelBundle(List.of(), List.of());
        }
        return runtimeBundleForDefaultModel(provider, modelRecord);
    }

    public RuntimeModelBundle buildRuntimeBundleForModel(String modelCode) {
        RuntimeModelBundle configuredBundle = runtimeBundleForConfiguredDefaultModel(modelCode);
        if (!configuredBundle.modelRecords().isEmpty()) {
            return configuredBundle;
        }
        RuntimeModelBundle embeddingBundle = runtimeBundleForConfiguredEmbeddingFallback(modelCode);
        if (!embeddingBundle.modelRecords().isEmpty()) {
            return embeddingBundle;
        }
        return buildDefaultRuntimeBundle();
    }

    public String resolveConfiguredPurposeModelCode(String purpose) {
        return defaultModelProperties.resolveModelCode(purpose);
    }

    public boolean isModelCodeAvailable(String modelCode) {
        String normalizedModelCode = blankToNull(modelCode);
        if (normalizedModelCode == null) {
            return false;
        }
        return modelRecordRepository.findByModelCode(normalizedModelCode)
                .filter(LlmModelRecord::isEnabled)
                .isPresent();
    }

    public String resolveRoutingModelCode(Collection<Map<String, Object>> workflowDefinitions) {
        for (Map<String, Object> workflowDefinition : workflowDefinitions) {
            String modelCode = findRoutingModelCode(workflowDefinition);
            if (modelCode != null && !modelCode.isBlank()) {
                return modelCode;
            }
        }
        return null;
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
        log.info(
                "model.provider.save.start userId={} providerCode={} providerType={} baseUrl={} enabled={}",
                userId,
                providerCode,
                request.getProviderType(),
                request.getBaseUrl(),
                request.getEnabled()
        );
        LlmProviderConfig provider = new LlmProviderConfig();
        applyProviderRequest(provider, request, true);
        provider.setCreatedBy(normalizeUserId(userId));
        provider.setCreatedAt(LocalDateTime.now());
        provider.setUpdatedAt(LocalDateTime.now());
        LlmProviderConfig saved = providerRepository.save(provider);
        log.info(
                "model.provider.save.persisted providerCode={} id={} type={} secretMode={} enabled={}",
                saved.getProviderCode(),
                saved.getId(),
                saved.getProviderType(),
                secretMode(saved.getApiKeySecretRef()),
                saved.isEnabled()
        );
        auditService.logAction(ApplicationConstants.DEFAULT_WORKSPACE_ID, normalizeUserId(userId), "model.provider.create", "llm_provider_config", providerCode, null, ApplicationConstants.HTTP_STATUS_OK);
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
        auditService.logAction(ApplicationConstants.DEFAULT_WORKSPACE_ID, normalizeUserId(userId), "model.provider.update", "llm_provider_config", providerCode, null, ApplicationConstants.HTTP_STATUS_OK);
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
        auditService.logAction(ApplicationConstants.DEFAULT_WORKSPACE_ID, normalizeUserId(userId), "model.provider.delete", "llm_provider_config", providerCode, null, ApplicationConstants.HTTP_STATUS_OK);
    }

    public Map<String, Object> validateProviderConfig(String userId, String providerCode, ValidateModelProviderRequest request) {
        requireAdmin(userId, "model.provider.validate");
        LlmProviderConfig provider = providerRepository.findByProviderCode(providerCode)
                .orElseThrow(() -> badRequest("Provider not found: " + providerCode));
        String modelCode = resolveProviderValidationModelCode(providerCode, request);
        log.info(
                "model.provider.validate.prepare providerCode={} providerType={} modelCode={} customBody={}",
                providerCode,
                provider.getProviderType(),
                modelCode,
                request.getRequestBody() != null && !request.getRequestBody().isEmpty()
        );
        int statusCode = unifiedModelService.validateProviderConnection(provider, modelCode, request.getRequestBody());
        log.info("model.provider.validate.done providerCode={} modelCode={} statusCode={}", providerCode, modelCode, statusCode);
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
        log.info(
                "model.record.save.persisted modelCode={} providerCode={} upstreamModelCode={} enabled={} defaultOptionsLength={}",
                saved.getModelCode(),
                saved.getProviderCode(),
                saved.getUpstreamModelCode(),
                saved.isEnabled(),
                saved.getDefaultOptionsJson() == null ? 0 : saved.getDefaultOptionsJson().length()
        );
        upsertInternalProvider(saved, userId);
        log.info("model.record.internal_provider.synced modelCode={} providerCode={}", saved.getModelCode(), saved.getProviderCode());
        auditService.logAction(ApplicationConstants.DEFAULT_WORKSPACE_ID, normalizeUserId(userId), "model.record.create", "llm_model_record", modelCode, null, ApplicationConstants.HTTP_STATUS_OK);
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
        auditService.logAction(ApplicationConstants.DEFAULT_WORKSPACE_ID, normalizeUserId(userId), "model.record.update", "llm_model_record", saved.getModelCode(), null, ApplicationConstants.HTTP_STATUS_OK);
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
        auditService.logAction(ApplicationConstants.DEFAULT_WORKSPACE_ID, normalizeUserId(userId), "model.record.delete", "llm_model_record", modelRecord.getModelCode(), null, ApplicationConstants.HTTP_STATUS_OK);
    }

    public Map<String, Object> validateModelRecord(String userId, String modelCode) {
        requireAdmin(userId, "model.record.validate");
        LlmModelRecord modelRecord = modelRecordRepository.findByModelCode(modelCode)
                .orElseThrow(() -> badRequest("Model record not found: " + modelCode));
        LlmProviderConfig provider = requireProvider(modelRecord.getProviderCode());
        String upstreamModelCode = firstNonBlank(modelRecord.getUpstreamModelCode(), modelRecord.getModelCode());
        log.info(
                "model.record.validate.prepare modelCode={} providerCode={} upstreamModelCode={}",
                modelRecord.getModelCode(),
                provider.getProviderCode(),
                upstreamModelCode
        );
        int statusCode = unifiedModelService.validateProviderConnection(
                provider,
                upstreamModelCode,
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
        log.info(
                "model.record.test.prepare modelCode={} messageCount={} hasOptions={} hasSystemPrompt={}",
                modelCode,
                messages.size(),
                request.getOptions() != null && !request.getOptions().isEmpty(),
                request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()
        );
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
        log.info(
                "model.record.test.done modelCode={} providerCode={} answerLength={} usageKeys={}",
                result.modelCode(),
                result.providerCode(),
                result.text() == null ? 0 : result.text().length(),
                result.usage() == null ? java.util.Set.of() : result.usage().keySet()
        );
        return response;
    }

    public Map<String, Object> testSimpleModelConnection(String userId, UpsertModelRecordRequest request) {
        // requireAdmin(userId, "model.record.test_chat");
        String providerType = required(firstNonBlank(request.getProvider(), request.getProviderCode()), "provider");
        String baseUrl = required(request.getBaseUrl(), "base_url").replaceAll("/+$", "");
        String apiKey = required(request.getApiKey(), "api_key");
        String modelName = required(request.getModelName(), "model_name");
        Map<String, Object> defaultOptions = objectToMap(request.getDefaultOptions());
        boolean embeddingModel = isEmbeddingModel(modelName, baseUrl, defaultOptions);
        UnifiedModelResult result = embeddingModel
                ? unifiedModelService.invokeDirectEmbedding(
                        providerType,
                        baseUrl,
                        apiKey,
                        modelName,
                        defaultOptions
                )
                : unifiedModelService.invokeDirectChat(
                        providerType,
                        baseUrl,
                        apiKey,
                        modelName,
                        List.of(Map.of("role", "user", "content", "ping")),
                        defaultOptions
                );
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("provider", providerType);
        response.put("model_name", modelName);
        response.put("model_type", embeddingModel ? "embedding" : "chat");
        response.put("answer", result.text());
        response.put("usage", result.usage());
        return response;
    }

    private boolean isEmbeddingModel(String modelName, String baseUrl, Map<String, Object> defaultOptions) {
        String normalizedModelName = blankToNull(modelName);
        String normalizedBaseUrl = blankToNull(baseUrl);
        if (normalizedModelName != null && normalizedModelName.toLowerCase(Locale.ROOT).contains("embedding")) {
            return true;
        }
        if (normalizedBaseUrl != null && normalizedBaseUrl.toLowerCase(Locale.ROOT).contains("embeddings")) {
            return true;
        }
        if (defaultOptions != null) {
            Object input = defaultOptions.get("input");
            if (input != null) {
                return true;
            }
            Object encodingFormat = defaultOptions.get("encoding_format");
            if (encodingFormat != null) {
                return true;
            }
            Object dimensions = defaultOptions.get("dimensions");
            if (dimensions != null) {
                return true;
            }
            Object embeddingDimension = defaultOptions.get("embedding_dimension");
            if (embeddingDimension != null) {
                return true;
            }
        }
        return false;
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
        provider.setDefaultModelCode(firstNonBlank(blankToNull(provider.getDefaultModelCode()), "gpt-4o-mini"));
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
        if (creating || request.getCapabilities() != null) {
            modelRecord.setCapabilitiesJson(writeJson(request.getCapabilities()));
        }
        if (creating || request.getDefaultSystemPrompt() != null) {
            modelRecord.setDefaultSystemPrompt(blankToNull(request.getDefaultSystemPrompt()));
        }
        if (creating || request.getDefaultOptions() != null) {
            modelRecord.setDefaultOptionsJson(writeJson(request.getDefaultOptions()));
        }
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
        value.put("default_model_code", provider.getDefaultModelCode());
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
        value.put("default_options", parseJson(modelRecord.getDefaultOptionsJson()));
        value.put("created_at", modelRecord.getCreatedAt());
        value.put("updated_at", modelRecord.getUpdatedAt());
        return value;
    }

    private RuntimeModelBundle runtimeBundleForDefaultModel(LlmProviderConfig provider, LlmModelRecord modelRecord) {
        log.info(
                "model.runtime.default.ready providerCode={} modelCode={}",
                provider.getProviderCode(),
                modelRecord.getModelCode()
        );
        return new RuntimeModelBundle(
                List.of(providerToRuntimeMap(provider)),
                List.of(modelRecordToRuntimeMap(modelRecord, provider))
        );
    }

    private RuntimeModelBundle runtimeBundleForConfiguredDefaultModel(String modelCode) {
        String normalizedModelCode = blankToNull(modelCode);
        if (normalizedModelCode == null) {
            return new RuntimeModelBundle(List.of(), List.of());
        }
        LlmModelRecord modelRecord = modelRecordRepository.findByModelCode(normalizedModelCode)
                .filter(LlmModelRecord::isEnabled)
                .orElse(null);
        if (modelRecord == null) {
            log.warn("model.runtime.default.configured_missing modelCode={}", normalizedModelCode);
            return new RuntimeModelBundle(List.of(), List.of());
        }
        LlmProviderConfig provider = providerRepository.findByProviderCode(modelRecord.getProviderCode()).orElse(null);
        if (provider == null || !provider.isEnabled()) {
            log.warn(
                    "model.runtime.default.configured_provider_unavailable modelCode={} providerCode={}",
                    normalizedModelCode,
                    modelRecord.getProviderCode()
            );
            return new RuntimeModelBundle(List.of(), List.of());
        }
        log.info("model.runtime.default.configured_ready providerCode={} modelCode={}", provider.getProviderCode(), normalizedModelCode);
        return runtimeBundleForDefaultModel(provider, modelRecord);
    }

    private RuntimeModelBundle runtimeBundleForConfiguredEmbeddingFallback(String modelCode) {
        KnowledgeProperties.Embedding embedding = knowledgeProperties.getEmbedding();
        String normalizedModelCode = blankToNull(modelCode);
        if (normalizedModelCode == null || !normalizedModelCode.equals(embedding.getDefaultModelCode())) {
            return new RuntimeModelBundle(List.of(), List.of());
        }

        Map<String, Object> provider = new LinkedHashMap<>();
        provider.put("provider_code", embedding.getProviderCode());
        provider.put("provider_name", embedding.getProviderName());
        provider.put("provider_type", embedding.getProviderType());
        provider.put("base_url", embedding.getBaseUrl());
        provider.put("api_key_secret_ref", embedding.getApiKeySecretRef());
        provider.put("default_model_code", embedding.getDefaultModelCode());
        provider.put("enabled", true);
        provider.put("extra_headers", Map.of("__meta__", Map.of("embedding_path", embedding.getEmbeddingPath())));

        Map<String, Object> defaultOptions = new LinkedHashMap<>();
        defaultOptions.put("embedding_dimension", embedding.getDimension());
        defaultOptions.put("encoding_format", embedding.getEncodingFormat());
        defaultOptions.put("include_messages", embedding.isIncludeMessages());
        defaultOptions.put("single_input_as_string", embedding.isSingleInputAsString());
        defaultOptions.put("timeout_sec", embedding.getTimeoutMs() / 1000);

        Map<String, Object> modelRecord = new LinkedHashMap<>();
        modelRecord.put("model_code", embedding.getDefaultModelCode());
        modelRecord.put("model_name", embedding.getDefaultUpstreamModel());
        modelRecord.put("provider_code", embedding.getProviderCode());
        modelRecord.put("provider_type", embedding.getProviderType());
        modelRecord.put("upstream_model_code", embedding.getDefaultUpstreamModel());
        modelRecord.put("capabilities", Map.of("embedding", true));
        modelRecord.put("default_system_prompt", "");
        modelRecord.put("default_options", defaultOptions);
        modelRecord.put("enabled", true);
        return new RuntimeModelBundle(List.of(provider), List.of(modelRecord));
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

    private Map<String, Object> objectToMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return result;
        }
        try {
            if (value instanceof String text) {
                if (text.isBlank()) {
                    return Map.of();
                }
                JsonNode node = objectMapper.readTree(text);
                if (node.isTextual()) {
                    node = objectMapper.readTree(node.asText());
                }
                return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
            }
            return objectMapper.convertValue(value, new TypeReference<Map<String, Object>>() {});
        } catch (Exception exception) {
            throw badRequest("Invalid default_options JSON payload");
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
        provider.setDefaultModelCode(required(modelRecord.getModelCode(), "model_code"));
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
        for (ApiItem apiItem : apiItemRepository.findAll()) {
            if (jsonContainsModelReference(apiItem.getInputSchema(), modelCode) || jsonContainsModelReference(apiItem.getOutputSchema(), modelCode)) {
                references.add("api_item:" + apiItem.getId());
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
