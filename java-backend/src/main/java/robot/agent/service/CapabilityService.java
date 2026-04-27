package robot.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import robot.agent.model.CapabilityAuthConfig;
import robot.agent.model.CapabilityGroup;
import robot.agent.model.CapabilityGroupSnapshot;
import robot.agent.model.CapabilityItem;
import robot.agent.model.CapabilityTestRecord;
import robot.agent.model.CapabilityType;
import robot.agent.repository.CapabilityAuthConfigRepository;
import robot.agent.repository.CapabilityGroupRepository;
import robot.agent.repository.CapabilityGroupSnapshotRepository;
import robot.agent.repository.CapabilityItemRepository;
import robot.agent.repository.CapabilityTestRecordRepository;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Transactional
public class CapabilityService {

    private static final Duration API_TEST_TIMEOUT = Duration.ofSeconds(5);
    private static final EnumSet<CapabilityType> CURRENTLY_SUPPORTED_CAPABILITY_TYPES = EnumSet.of(CapabilityType.API);

    private final CapabilityGroupRepository groupRepository;
    private final CapabilityItemRepository itemRepository;
    private final CapabilityGroupSnapshotRepository snapshotRepository;
    private final CapabilityAuthConfigRepository authConfigRepository;
    private final CapabilityTestRecordRepository testRecordRepository;
    private final ObjectMapper objectMapper;

    public CapabilityService(
            CapabilityGroupRepository groupRepository,
            CapabilityItemRepository itemRepository,
            CapabilityGroupSnapshotRepository snapshotRepository,
            CapabilityAuthConfigRepository authConfigRepository,
            CapabilityTestRecordRepository testRecordRepository,
            ObjectMapper objectMapper
    ) {
        this.groupRepository = groupRepository;
        this.itemRepository = itemRepository;
        this.snapshotRepository = snapshotRepository;
        this.authConfigRepository = authConfigRepository;
        this.testRecordRepository = testRecordRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getCapabilityGroups() {
        return groupRepository.findAllByOrderByUpdatedAtDesc().stream()
                .map(this::toGroupSummary)
                .toList();
    }

    public Map<String, Object> saveCapabilityGroup(Map<String, Object> payload, Long existingGroupId) {
        CapabilityGroup group = existingGroupId == null
                ? new CapabilityGroup()
                : groupRepository.findById(existingGroupId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "能力组不存在: " + existingGroupId));

        if (group.getGroupCode() == null || group.getGroupCode().isBlank()) {
            group.setGroupCode(generateGroupCode());
        }
        if (group.getDomainCode() == null || group.getDomainCode().isBlank()) {
            group.setDomainCode(group.getGroupCode());
        }
        group.setGroupName(requiredString(payload, "groupName"));
        group.setDescription(optionalString(payload.get("description")));
        if (group.getCreatedAt() == null) {
            group.setCreatedAt(LocalDateTime.now());
        }
        group.setUpdatedAt(LocalDateTime.now());
        group.setStatus(group.getStatus() == null ? "DRAFT" : group.getStatus());
        return toGroupSummary(groupRepository.save(group));
    }

    public void deleteCapabilityGroup(Long groupId) {
        CapabilityGroup group = requireGroup(groupId);
        String groupCode = group.getGroupCode();
        snapshotRepository.deleteByGroupCode(groupCode);
        authConfigRepository.deleteByGroupCode(groupCode);
        itemRepository.deleteByGroupCode(groupCode);
        testRecordRepository.deleteByGroupCode(groupCode);
        groupRepository.delete(group);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getCapabilitiesByGroup(Long groupId) {
        CapabilityGroup group = requireGroup(groupId);
        return itemRepository.findByGroupCodeOrderByUpdatedAtDesc(group.getGroupCode()).stream()
                .map(item -> toItemSummary(groupId, item))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getCapabilityVersions(Long groupId, String capabilityCode) {
        CapabilityItem item = requireItem(groupId, capabilityCode);
        List<Map<String, Object>> versions = new ArrayList<>();
        if (item.getDraftVersion() != null && !item.getDraftVersion().isBlank()) {
            versions.add(toVersionSummary(groupId, item, item.getDraftVersion(), "DRAFT"));
        }
        if (item.getPublishedVersion() != null && !item.getPublishedVersion().isBlank()) {
            versions.add(toVersionSummary(groupId, item, item.getPublishedVersion(), "PUBLISHED"));
        }
        return versions;
    }

    public Map<String, Object> saveCapabilityDraft(Long groupId, String existingCapabilityCode, Map<String, Object> payload) {
        CapabilityGroup group = requireGroup(groupId);
        Map<String, Object> validation = validateCapabilityDraft(groupId, payload);
        if (!Boolean.TRUE.equals(validation.get("valid"))) {
            throw new ResponseStatusException(BAD_REQUEST, firstNonBlank(resolveValidationErrorMessage(validation), "能力草稿校验失败"));
        }

        String groupCode = group.getGroupCode();
        String capabilityCode = existingCapabilityCode != null
                ? existingCapabilityCode
                : firstNonBlank(optionalString(payload.get("capabilityCode")), generateCapabilityCode());
        CapabilityItem item = itemRepository.findByGroupCodeAndCapabilityCode(groupCode, capabilityCode).orElseGet(CapabilityItem::new);
        item.setGroupCode(groupCode);
        item.setCapabilityCode(capabilityCode);
        item.setCapabilityName(requiredString(payload, "capabilityName"));
        item.setCapabilityType(parseCapabilityType(payload.get("capabilityType")));
        item.setDescription(optionalString(payload.get("description")));
        item.setDefinitionJson(stringOrJson(payload.get("definitionJson")));
        item.setInputSchema(optionalString(payload.get("inputSchema")));
        item.setOutputSchema(optionalString(payload.get("outputSchema")));
        item.setAuthConfigId(requireAuthConfig(group, optionalLong(payload.get("authConfigId"))).getId());
        item.setStatus("DRAFT");
        item.setDraftVersion(firstNonBlank(optionalString(payload.get("version")), "draft"));
        if (item.getCreatedAt() == null) {
            item.setCreatedAt(LocalDateTime.now());
        }
        item.setUpdatedAt(LocalDateTime.now());
        return toVersionSummary(groupId, itemRepository.save(item), item.getDraftVersion(), "DRAFT");
    }

    public Map<String, Object> publishCapability(Long groupId, String capabilityCode) {
        CapabilityItem item = requireItem(groupId, capabilityCode);
        ensureCapabilityTypeSupported(item.getCapabilityType());
        if (item.getDraftVersion() == null || item.getDraftVersion().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "请先保存草稿后再发布能力");
        }
        String version = createVersion();
        item.setPublishedVersion(version);
        item.setStatus("PUBLISHED");
        item.setUpdatedAt(LocalDateTime.now());
        return toVersionSummary(groupId, itemRepository.save(item), version, "PUBLISHED");
    }

    public void deleteCapability(Long groupId, String capabilityCode) {
        CapabilityItem item = requireItem(groupId, capabilityCode);
        itemRepository.deleteByGroupCodeAndCapabilityCode(item.getGroupCode(), capabilityCode);
        testRecordRepository.deleteByCapabilityCode(capabilityCode);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getCapabilityGroupSnapshots(Long groupId) {
        CapabilityGroup group = requireGroup(groupId);
        return snapshotRepository.findByGroupCodeOrderByPublishedAtDesc(group.getGroupCode()).stream()
                .map(snapshot -> toSnapshotSummary(groupId, snapshot))
                .toList();
    }

    public Map<String, Object> publishCapabilityGroupSnapshot(Long groupId, Map<String, Object> payload) {
        CapabilityGroup group = requireGroup(groupId);
        String groupCode = group.getGroupCode();
        List<CapabilityItem> publishedItems = itemRepository.findByGroupCodeOrderByUpdatedAtDesc(groupCode).stream()
                .filter(item -> isCapabilityTypeSupported(item.getCapabilityType()))
                .filter(item -> item.getPublishedVersion() != null && !item.getPublishedVersion().isBlank())
                .toList();
        if (publishedItems.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "发布能力组前，至少需要一个已发布的 API 能力");
        }

        snapshotRepository.deleteByGroupCode(groupCode);

        CapabilityGroupSnapshot snapshot = new CapabilityGroupSnapshot();
        snapshot.setGroupCode(groupCode);
        snapshot.setSnapshotVersion("published");
        snapshot.setStatus("PUBLISHED");
        snapshot.setDescription(optionalString(payload.get("description")));
        snapshot.setSnapshotPayload(writeJson(Map.of(
                "groupId", groupId,
                "groupName", group.getGroupName(),
                "capabilities", publishedItems.stream().map(item -> Map.of(
                        "capabilityCode", item.getCapabilityCode(),
                        "capabilityName", item.getCapabilityName(),
                        "capabilityType", item.getCapabilityType().name(),
                        "version", item.getPublishedVersion(),
                        "definitionJson", firstNonBlank(item.getDefinitionJson(), "{}"),
                        "authConfigId", item.getAuthConfigId()
                )).toList()
        )));
        snapshot.setPublishedAt(LocalDateTime.now());
        group.setStatus("PUBLISHED");
        group.setUpdatedAt(LocalDateTime.now());
        groupRepository.save(group);
        return toSnapshotSummary(groupId, snapshotRepository.save(snapshot));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getCapabilityAuthConfigs(Long groupId) {
        CapabilityGroup group = requireGroup(groupId);
        return authConfigRepository.findByGroupCodeOrderByUpdatedAtDesc(group.getGroupCode()).stream()
                .map(this::toAuthSummary)
                .toList();
    }

    public Map<String, Object> saveCapabilityAuthConfig(Long groupId, Long authConfigId, Map<String, Object> payload) {
        CapabilityGroup group = requireGroup(groupId);
        String groupCode = group.getGroupCode();
        CapabilityAuthConfig authConfig = authConfigId == null
                ? new CapabilityAuthConfig()
                : authConfigRepository.findById(authConfigId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "认证配置不存在: " + authConfigId));
        authConfig.setGroupCode(groupCode);
        authConfig.setAuthName(requiredString(payload, "authName"));
        authConfig.setAuthType(requiredString(payload, "authType"));
        authConfig.setScope("GROUP");
        authConfig.setStatus(firstNonBlank(optionalString(payload.get("status")), "ACTIVE"));
        boolean hasConfigPayload = payload.containsKey("config");
        if (hasConfigPayload || authConfigId == null) {
            authConfig.setConfigJson(writeJson(payload.get("config")));
            authConfig.setMaskedPreview(firstNonBlank(optionalString(payload.get("maskedPreview")), maskConfigPreview(payload.get("config"))));
        } else if (payload.containsKey("maskedPreview")) {
            authConfig.setMaskedPreview(optionalString(payload.get("maskedPreview")));
        }
        if (authConfig.getCreatedAt() == null) {
            authConfig.setCreatedAt(LocalDateTime.now());
        }
        authConfig.setUpdatedAt(LocalDateTime.now());
        return toAuthSummary(authConfigRepository.save(authConfig));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateCapabilityDraft(Long groupId, Map<String, Object> payload) {
        CapabilityGroup group = requireGroup(groupId);
        CapabilityType type = parseCapabilityType(payload.get("capabilityType"));
        Map<String, Object> definition = parseObject(optionalString(payload.get("definitionJson")));
        List<Map<String, Object>> issues = new ArrayList<>();

        if (!isCapabilityTypeSupported(type)) {
            issues.add(issue("capabilityType", "能力中心当前仅支持 API 能力"));
        }

        switch (type) {
            case API -> {
                if (blank(stringValue(definition.get("url")))) {
                    issues.add(issue("definition.url", "API 能力缺少 URL"));
                }
                if (blank(stringValue(definition.get("method")))) {
                    issues.add(issue("definition.method", "API 能力缺少请求方法"));
                }
            }
            case SKILL -> {
                if (blank(stringValue(definition.get("skill_name")))) {
                    issues.add(issue("definition.skill_name", "Skill 能力缺少 skill_name"));
                }
                if (blank(stringValue(definition.get("executor_type")))) {
                    issues.add(issue("definition.executor_type", "Skill 能力缺少 executor_type"));
                }
            }
            case MCP -> {
                if (blank(stringValue(definition.get("server_url")))) {
                    issues.add(issue("definition.server_url", "MCP 能力缺少 server_url"));
                }
                if (blank(stringValue(definition.get("protocol")))) {
                    issues.add(issue("definition.protocol", "MCP 能力缺少 protocol"));
                }
            }
        }

        if (type == CapabilityType.API) {
            Long authConfigId = optionalLong(payload.get("authConfigId"));
            if (authConfigId == null) {
                issues.add(issue("authConfigId", "能力必须绑定一个已配置的认证"));
            } else {
                try {
                    requireAuthConfig(group, authConfigId);
                } catch (ResponseStatusException exception) {
                    issues.add(issue("authConfigId", firstNonBlank(exception.getReason(), "认证配置不可用")));
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", issues.isEmpty());
        result.put("message", issues.isEmpty() ? "校验通过" : "校验失败");
        result.put("issues", issues);
        return result;
    }

    public Map<String, Object> testCapability(Long groupId, String capabilityCode, Map<String, Object> payload) {
        CapabilityGroup group = requireGroup(groupId);
        CapabilityItem item = requireItem(groupId, capabilityCode);
        Map<String, Object> validationPayload = new LinkedHashMap<>();
        validationPayload.put("capabilityType", item.getCapabilityType().name());
        validationPayload.put("definitionJson", firstNonBlank(item.getDefinitionJson(), "{}"));
        validationPayload.put("authConfigId", item.getAuthConfigId());
        Map<String, Object> validation = validateCapabilityDraft(groupId, validationPayload);
        Map<String, Object> result;
        if (item.getCapabilityType() == CapabilityType.API) {
            CapabilityAuthConfig authConfig = requireAuthConfig(group, item.getAuthConfigId());
            result = runApiTest(payload, validation, item, authConfig);
        } else {
            result = runValidationOnlyTest(payload, validation);
        }
        item.setLastTestStatus(Boolean.TRUE.equals(result.get("success")) ? "SUCCESS" : "FAILED");
        item.setLastTestTime(LocalDateTime.now());
        itemRepository.save(item);
        persistTestRecord(group.getGroupCode(), item, result);
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getCapabilityTestRecords(Long groupId) {
        CapabilityGroup group = requireGroup(groupId);
        return testRecordRepository.findByGroupCodeOrderByCreatedAtDesc(group.getGroupCode()).stream()
                .map(record -> toTestRecordSummary(groupId, record))
                .toList();
    }

    @Transactional(readOnly = true)
    public String resolveGroupCode(Long groupId) {
        return requireGroup(groupId).getGroupCode();
    }

    private CapabilityGroup requireGroup(Long groupId) {
        return groupRepository.findById(groupId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "能力组不存在: " + groupId));
    }

    private CapabilityItem requireItem(Long groupId, String capabilityCode) {
        CapabilityGroup group = requireGroup(groupId);
        return itemRepository.findByGroupCodeAndCapabilityCode(group.getGroupCode(), capabilityCode)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "能力项不存在: " + capabilityCode));
    }

    private CapabilityAuthConfig requireAuthConfig(CapabilityGroup group, Long authConfigId) {
        if (authConfigId == null) {
            throw new ResponseStatusException(BAD_REQUEST, "authConfigId 不能为空");
        }
        CapabilityAuthConfig authConfig = authConfigRepository.findById(authConfigId)
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "认证配置不存在: " + authConfigId));
        if (!group.getGroupCode().equals(authConfig.getGroupCode())) {
            throw new ResponseStatusException(BAD_REQUEST, "认证配置不属于当前能力组");
        }
        if ("DISABLED".equalsIgnoreCase(authConfig.getStatus())) {
            throw new ResponseStatusException(BAD_REQUEST, "认证配置已停用");
        }
        return authConfig;
    }

    private Map<String, Object> toGroupSummary(CapabilityGroup group) {
        CapabilityGroupSnapshot snapshot = snapshotRepository.findFirstByGroupCodeOrderByPublishedAtDesc(group.getGroupCode());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", group.getId());
        result.put("groupName", group.getGroupName());
        result.put("description", group.getDescription());
        result.put("status", group.getStatus());
        result.put("capabilityCount", itemRepository.countByGroupCode(group.getGroupCode()));
        result.put("latestSnapshotVersion", snapshot == null ? null : snapshot.getSnapshotVersion());
        result.put("latestPublishedAt", snapshot == null ? null : snapshot.getPublishedAt());
        result.put("updatedAt", group.getUpdatedAt());
        return result;
    }

    private Map<String, Object> toItemSummary(Long groupId, CapabilityItem item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", item.getId());
        result.put("groupId", groupId);
        result.put("capabilityCode", item.getCapabilityCode());
        result.put("capabilityName", item.getCapabilityName());
        result.put("capabilityType", item.getCapabilityType().name());
        result.put("status", item.getStatus());
        result.put("draftVersion", item.getDraftVersion());
        result.put("publishedVersion", item.getPublishedVersion());
        result.put("lastTestStatus", item.getLastTestStatus());
        result.put("lastTestTime", item.getLastTestTime());
        result.put("description", item.getDescription());
        result.put("authConfigId", item.getAuthConfigId());
        return result;
    }

    private Map<String, Object> toVersionSummary(Long groupId, CapabilityItem item, String version, String status) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", item.getId());
        result.put("groupId", groupId);
        result.put("capabilityCode", item.getCapabilityCode());
        result.put("capabilityName", item.getCapabilityName());
        result.put("capabilityType", item.getCapabilityType().name());
        result.put("version", version);
        result.put("status", status);
        result.put("description", item.getDescription());
        result.put("definitionJson", item.getDefinitionJson());
        result.put("inputSchema", item.getInputSchema());
        result.put("outputSchema", item.getOutputSchema());
        result.put("authConfigId", item.getAuthConfigId());
        result.put("publishedAt", "PUBLISHED".equals(status) ? item.getUpdatedAt() : null);
        result.put("updatedAt", item.getUpdatedAt());
        return result;
    }

    private Map<String, Object> toSnapshotSummary(Long groupId, CapabilityGroupSnapshot snapshot) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", snapshot.getId());
        result.put("groupId", groupId);
        result.put("snapshotVersion", snapshot.getSnapshotVersion());
        result.put("status", snapshot.getStatus());
        result.put("description", snapshot.getDescription());
        result.put("publishedAt", snapshot.getPublishedAt());
        return result;
    }

    private Map<String, Object> toAuthSummary(CapabilityAuthConfig authConfig) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", authConfig.getId());
        result.put("authName", authConfig.getAuthName());
        result.put("authType", authConfig.getAuthType());
        result.put("maskedPreview", authConfig.getMaskedPreview());
        result.put("scope", authConfig.getScope());
        result.put("status", authConfig.getStatus());
        result.put("updatedAt", authConfig.getUpdatedAt());
        return result;
    }

    private Map<String, Object> toTestRecordSummary(Long groupId, CapabilityTestRecord record) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", record.getId());
        result.put("groupId", groupId);
        result.put("capabilityCode", record.getCapabilityCode());
        result.put("capabilityName", record.getCapabilityName());
        result.put("capabilityType", record.getCapabilityType());
        result.put("capabilityVersion", record.getCapabilityVersion());
        result.put("testType", record.getTestType());
        result.put("requestPayload", record.getRequestPayload());
        result.put("responsePayload", record.getResponsePayload());
        result.put("success", record.isSuccess());
        result.put("errorMessage", record.getErrorMessage());
        result.put("durationMs", record.getDurationMs());
        result.put("createdBy", record.getCreatedBy());
        result.put("testedAt", record.getCreatedAt());
        return result;
    }

    private void persistTestRecord(String groupCode, CapabilityItem item, Map<String, Object> result) {
        CapabilityTestRecord record = new CapabilityTestRecord();
        record.setGroupCode(groupCode);
        record.setCapabilityCode(item.getCapabilityCode());
        record.setCapabilityName(item.getCapabilityName());
        record.setCapabilityType(item.getCapabilityType().name());
        record.setCapabilityVersion(firstNonBlank(item.getPublishedVersion(), item.getDraftVersion()));
        record.setTestType(String.valueOf(result.get("testType")));
        record.setRequestPayload(stringOrJson(result.get("requestPayload")));
        record.setResponsePayload(stringOrJson(result.get("responsePayload")));
        record.setSuccess(Boolean.TRUE.equals(result.get("success")));
        record.setErrorMessage(optionalString(result.get("errorMessage")));
        record.setDurationMs(optionalLong(result.get("durationMs")));
        testRecordRepository.save(record);
    }

    private Map<String, Object> runValidationOnlyTest(Map<String, Object> payload, Map<String, Object> validation) {
        boolean success = Boolean.TRUE.equals(validation.get("valid"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("testType", firstNonBlank(optionalString(payload.get("testType")), "validate"));
        result.put("requestPayload", writeJson(payload));
        result.put("responsePayload", success ? writeJson(Map.of("message", "测试通过")) : writeJson(validation));
        result.put("errorMessage", success ? null : resolveValidationErrorMessage(validation));
        result.put("durationMs", 0L);
        result.put("testedAt", LocalDateTime.now());
        return result;
    }

    private String resolveValidationErrorMessage(Map<String, Object> validation) {
        Object rawIssues = validation.get("issues");
        if (rawIssues instanceof List<?> issues) {
            for (Object rawIssue : issues) {
                if (rawIssue instanceof Map<?, ?> issue) {
                    Object message = issue.get("message");
                    if (message != null) {
                        return String.valueOf(message);
                    }
                }
            }
        }
        return "能力配置未通过校验";
    }

    private Map<String, Object> runApiTest(
            Map<String, Object> payload,
            Map<String, Object> validation,
            CapabilityItem item,
            CapabilityAuthConfig authConfig
    ) {
        if (!Boolean.TRUE.equals(validation.get("valid"))) {
            return runValidationOnlyTest(payload, validation);
        }

        Map<String, Object> definition = parseObject(firstNonBlank(item.getDefinitionJson(), "{}"));
        String url = requiredString(definition, "url");
        String method = firstNonBlank(optionalString(definition.get("method")), "GET").toUpperCase(Locale.ROOT);
        Map<String, String> headers = normalizeHeaders(definition.get("headers"));
        headers.putAll(resolveAuthHeaders(authConfig));

        Map<String, Object> requestPayload = new LinkedHashMap<>();
        requestPayload.put("testType", firstNonBlank(optionalString(payload.get("testType")), "request"));
        requestPayload.put("url", url);
        requestPayload.put("method", method);
        requestPayload.put("headers", headers);

        long startedAt = System.nanoTime();
        try {
            HttpRequest request = buildApiTestRequest(url, method, headers);
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(API_TEST_TIMEOUT)
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", success);
            result.put("testType", "request");
            result.put("requestPayload", writeJson(requestPayload));
            result.put("responsePayload", writeJson(Map.of(
                    "statusCode", response.statusCode(),
                    "responseBody", parseJsonOrRaw(response.body())
            )));
            result.put("errorMessage", success ? null : "认证后的接口请求返回非成功状态: " + response.statusCode());
            result.put("durationMs", Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
            result.put("testedAt", LocalDateTime.now());
            return result;
        } catch (Exception exception) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("testType", "request");
            result.put("requestPayload", writeJson(requestPayload));
            result.put("responsePayload", writeJson(Map.of(
                    "exception", exception.getClass().getSimpleName(),
                    "message", firstNonBlank(exception.getMessage(), "API 请求失败")
            )));
            result.put("errorMessage", firstNonBlank(exception.getMessage(), "API 请求失败"));
            result.put("durationMs", Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
            result.put("testedAt", LocalDateTime.now());
            return result;
        }
    }

    private HttpRequest buildApiTestRequest(String url, String method, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(API_TEST_TIMEOUT);
        headers.forEach(builder::header);
        builder.method(method, HttpRequest.BodyPublishers.noBody());
        return builder.build();
    }

    private Map<String, String> resolveAuthHeaders(CapabilityAuthConfig authConfig) {
        String authType = firstNonBlank(optionalString(authConfig.getAuthType()), "NONE").toUpperCase(Locale.ROOT);
        Map<String, Object> config = parseObject(authConfig.getConfigJson());
        Map<String, String> headers = new LinkedHashMap<>();
        switch (authType) {
            case "NONE" -> {
                return headers;
            }
            case "JWT", "OAUTH2" -> {
                String headerName = firstNonBlank(optionalString(config.get("headerName")), HttpHeaders.AUTHORIZATION);
                String token = firstNonBlank(optionalString(config.get("token")), optionalString(config.get("accessToken")));
                if (token == null) {
                    throw new ResponseStatusException(BAD_REQUEST, "认证配置缺少 token");
                }
                String scheme = firstNonBlank(optionalString(config.get("scheme")), "Bearer");
                headers.put(headerName, (scheme + " " + token).trim());
            }
            case "API_KEY" -> {
                String location = firstNonBlank(optionalString(config.get("location")), "HEADER").toUpperCase(Locale.ROOT);
                String keyName = firstNonBlank(optionalString(config.get("headerName")), "X-API-Key");
                String value = firstNonBlank(optionalString(config.get("value")), optionalString(config.get("apiKey")));
                if (value == null) {
                    throw new ResponseStatusException(BAD_REQUEST, "认证配置缺少 API Key");
                }
                if (!"HEADER".equals(location)) {
                    throw new ResponseStatusException(BAD_REQUEST, "当前仅支持请求头形式的 API Key");
                }
                headers.put(keyName, value);
            }
            case "BASIC", "PASSWORD" -> {
                String username = optionalString(config.get("username"));
                String password = optionalString(config.get("password"));
                if (username == null || password == null) {
                    throw new ResponseStatusException(BAD_REQUEST, "认证配置缺少用户名或密码");
                }
                String encoded = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
                headers.put(HttpHeaders.AUTHORIZATION, "Basic " + encoded);
            }
            case "CUSTOM" -> {
                Map<String, Object> headerMap = asObject(config.get("headers"));
                if (headerMap.isEmpty()) {
                    throw new ResponseStatusException(BAD_REQUEST, "自定义认证缺少 headers 配置");
                }
                headerMap.forEach((key, value) -> {
                    if (value != null) {
                        headers.put(key, String.valueOf(value));
                    }
                });
            }
            default -> throw new ResponseStatusException(BAD_REQUEST, "不支持的认证类型: " + authType);
        }
        return headers;
    }

    public Map<String, String> buildResolvedHeadersForCapability(Long groupId, String capabilityCode) {
        CapabilityGroup group = requireGroup(groupId);
        CapabilityItem item = requireItem(groupId, capabilityCode);
        CapabilityAuthConfig authConfig = requireAuthConfig(group, item.getAuthConfigId());
        Map<String, Object> definition = parseObject(firstNonBlank(item.getDefinitionJson(), "{}"));
        Map<String, String> headers = normalizeHeaders(definition.get("headers"));
        headers.putAll(resolveAuthHeaders(authConfig));
        return headers;
    }

    private Map<String, String> normalizeHeaders(Object headersConfig) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (!(headersConfig instanceof Map<?, ?> rawHeaders)) {
            return headers;
        }
        rawHeaders.forEach((key, value) -> {
            if (key != null && value != null) {
                headers.put(String.valueOf(key), String.valueOf(value));
            }
        });
        return headers;
    }

    private Object parseJsonOrRaw(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            return objectMapper.readValue(body, Object.class);
        } catch (Exception exception) {
            return body;
        }
    }

    private String generateCapabilityCode() {
        return "cap_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String generateGroupCode() {
        return "cap_group_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private Map<String, Object> issue(String field, String message) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("field", field);
        issue.put("message", message);
        return issue;
    }

    private boolean isCapabilityTypeSupported(CapabilityType capabilityType) {
        return CURRENTLY_SUPPORTED_CAPABILITY_TYPES.contains(capabilityType);
    }

    private void ensureCapabilityTypeSupported(CapabilityType capabilityType) {
        if (!isCapabilityTypeSupported(capabilityType)) {
            throw new ResponseStatusException(BAD_REQUEST, "当前不支持 " + capabilityType.name() + " 类型");
        }
    }

    private CapabilityType parseCapabilityType(Object rawValue) {
        String value = stringValue(rawValue);
        if (value == null) {
            throw new ResponseStatusException(BAD_REQUEST, "capabilityType 不能为空");
        }
        try {
            return CapabilityType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(BAD_REQUEST, "不支持的能力类型: " + value);
        }
    }

    private Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception exception) {
            throw new ResponseStatusException(BAD_REQUEST, "JSON 配置格式不正确");
        }
    }

    private String requiredString(Map<String, Object> payload, String key) {
        String value = optionalString(payload.get(key));
        if (value == null) {
            throw new ResponseStatusException(BAD_REQUEST, key + " 不能为空");
        }
        return value;
    }

    private String optionalString(Object value) {
        String text = stringValue(value);
        return text == null || text.isBlank() ? null : text.trim();
    }

    private String stringOrJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        return writeJson(value);
    }

    private Long optionalLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            throw new ResponseStatusException(BAD_REQUEST, "数字字段格式不正确");
        }
    }

    private String firstNonBlank(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary : fallback;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String maskConfigPreview(Object value) {
        if (value == null) {
            return "已配置";
        }
        return "已配置 / 已脱敏";
    }

    private Map<String, Object> asObject(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> result = new LinkedHashMap<>();
            source.forEach((key, item) -> {
                if (key != null) {
                    result.put(String.valueOf(key), item);
                }
            });
            return result;
        }
        return new LinkedHashMap<>();
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new ResponseStatusException(BAD_REQUEST, "JSON 数据无法序列化");
        }
    }

    private String createVersion() {
        LocalDateTime now = LocalDateTime.now();
        return String.format(
                "v%04d%02d%02d%02d%02d%02d",
                now.getYear(),
                now.getMonthValue(),
                now.getDayOfMonth(),
                now.getHour(),
                now.getMinute(),
                now.getSecond()
        );
    }
}
