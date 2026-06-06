package robot.agent.apicenter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import robot.agent.apicenter.model.ApiAuthMode;
import robot.agent.apicenter.model.ApiAuthScopeType;
import robot.agent.apicenter.model.ApiAuthType;
import robot.agent.apicenter.model.ApiGroup;
import robot.agent.apicenter.model.ApiItem;
import robot.agent.apicenter.repository.ApiGroupRepository;
import robot.agent.apicenter.repository.ApiItemRepository;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Transactional
public class ApiCenterService {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);
    private static final int MAX_RESPONSE_CHARS = 1_000_000;
    private static final String EMPTY_OBJECT_SCHEMA = "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"type\":\"object\",\"additionalProperties\":false}";

    private final ApiGroupRepository groupRepository;
    private final ApiItemRepository itemRepository;
    private final ApiSchemaValidator schemaValidator;
    private final ApiUrlTemplateResolver urlTemplateResolver;
    private final ApiHeaderCryptoService headerCryptoService;
    private final ApiRequestSafetyValidator requestSafetyValidator;
    private final ApiAuthConfigService authConfigService;
    private final ApiAuthResolver authResolver;
    private final ApiDigestAuthService digestAuthService;
    private final ObjectMapper objectMapper;

    public ApiCenterService(
            ApiGroupRepository groupRepository,
            ApiItemRepository itemRepository,
            ApiSchemaValidator schemaValidator,
            ApiUrlTemplateResolver urlTemplateResolver,
            ApiHeaderCryptoService headerCryptoService,
            ApiRequestSafetyValidator requestSafetyValidator,
            ApiAuthConfigService authConfigService,
            ApiAuthResolver authResolver,
            ApiDigestAuthService digestAuthService,
            ObjectMapper objectMapper
    ) {
        this.groupRepository = groupRepository;
        this.itemRepository = itemRepository;
        this.schemaValidator = schemaValidator;
        this.urlTemplateResolver = urlTemplateResolver;
        this.headerCryptoService = headerCryptoService;
        this.requestSafetyValidator = requestSafetyValidator;
        this.authConfigService = authConfigService;
        this.authResolver = authResolver;
        this.digestAuthService = digestAuthService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getGroups() {
        return groupRepository.findAllByOrderByUpdatedAtDesc().stream().map(this::toGroupSummary).toList();
    }

    public Map<String, Object> saveGroup(Map<String, Object> payload, Long groupId) {
        ApiGroup group = groupId == null ? new ApiGroup() : requireGroup(groupId);
        group.setGroupName(requiredString(payload, "groupName"));
        group.setDescription(optionalString(payload.get("description")));
        group.setEnabled(booleanValue(payload.get("enabled"), true));
        if (group.getCreatedAt() == null) {
            group.setCreatedAt(LocalDateTime.now());
        }
        group.setUpdatedAt(LocalDateTime.now());
        ApiGroup saved = groupRepository.save(group);
        if (payload.containsKey("authConfig")) {
            authConfigService.saveAuthConfig(ApiAuthScopeType.GROUP, saved.getId(), asObject(payload.get("authConfig")));
        }
        return toGroupSummary(saved);
    }

    public void deleteGroup(Long groupId) {
        ApiGroup group = requireGroup(groupId);
        itemRepository.findByGroupIdOrderByUpdatedAtDesc(group.getId())
                .forEach(item -> authConfigService.deleteAuthConfig(ApiAuthScopeType.ITEM, item.getId()));
        authConfigService.deleteAuthConfig(ApiAuthScopeType.GROUP, group.getId());
        itemRepository.deleteByGroupId(group.getId());
        groupRepository.delete(group);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getGroupAuthConfig(Long groupId) {
        requireGroup(groupId);
        return authConfigService.getAuthConfig(ApiAuthScopeType.GROUP, groupId);
    }

    public Map<String, Object> saveGroupAuthConfig(Long groupId, Map<String, Object> payload) {
        requireGroup(groupId);
        return authConfigService.saveAuthConfig(ApiAuthScopeType.GROUP, groupId, payload);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getItems(Long groupId) {
        requireGroup(groupId);
        return itemRepository.findByGroupIdOrderByUpdatedAtDesc(groupId).stream().map(this::toItemSummary).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getItem(Long groupId, Long apiId) {
        requireGroup(groupId);
        return toItemDetail(requireItem(groupId, apiId));
    }

    public Map<String, Object> saveItem(Long groupId, Long apiId, Map<String, Object> payload) {
        requireGroup(groupId);
        ApiItem item = apiId == null ? new ApiItem() : requireItem(groupId, apiId);
        applyItemPayload(groupId, item, payload);
        item.setLastTestStatus(null);
        item.setLastTestToken(null);
        item.setLastTestTime(null);
        item.setLastTestErrorMessage(null);
        ApiItem saved = itemRepository.save(item);
        Map<String, Object> savedAuthConfig = null;
        if (ApiAuthMode.CUSTOM.name().equals(saved.getAuthMode()) && payload.containsKey("authConfig")) {
            savedAuthConfig = authConfigService.saveAuthConfig(ApiAuthScopeType.ITEM, saved.getId(), asObject(payload.get("authConfig")));
        } else if (!ApiAuthMode.CUSTOM.name().equals(saved.getAuthMode())) {
            authConfigService.deleteAuthConfig(ApiAuthScopeType.ITEM, saved.getId());
        }
        Map<String, Object> result = toItemDetail(saved);
        if (savedAuthConfig != null) {
            result.put("authType", savedAuthConfig.get("authType"));
            result.put("authPreview", savedAuthConfig.get("authPreview"));
            result.put("authConfig", savedAuthConfig);
        }
        return result;
    }

    public void deleteItem(Long groupId, Long apiId) {
        ApiItem item = requireItem(groupId, apiId);
        authConfigService.deleteAuthConfig(ApiAuthScopeType.ITEM, item.getId());
        itemRepository.delete(item);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getItemAuthConfig(Long groupId, Long apiId) {
        ApiItem item = requireItem(groupId, apiId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("authMode", firstNonBlank(item.getAuthMode(), ApiAuthMode.INHERIT.name()));
        result.put("authConfig", authConfigService.getAuthConfig(ApiAuthScopeType.ITEM, apiId));
        ApiAuthConfigService.EffectiveAuth effectiveAuth = authConfigService.resolveEffectiveAuth(groupId, apiId, item.getAuthMode());
        result.putAll(authConfigService.responseForEffectiveAuth(effectiveAuth));
        return result;
    }

    public Map<String, Object> saveItemAuthConfig(Long groupId, Long apiId, Map<String, Object> payload) {
        ApiItem item = requireItem(groupId, apiId);
        ApiAuthMode authMode = authConfigService.parseAuthMode(payload.get("authMode"));
        item.setAuthMode(authMode.name());
        item.setUpdatedAt(LocalDateTime.now());
        itemRepository.save(item);
        if (authMode == ApiAuthMode.CUSTOM) {
            authConfigService.saveAuthConfig(ApiAuthScopeType.ITEM, apiId, asObject(payload.get("authConfig")));
        } else {
            authConfigService.deleteAuthConfig(ApiAuthScopeType.ITEM, apiId);
        }
        return getItemAuthConfig(groupId, apiId);
    }

    public Map<String, Object> validateDraft(Long groupId, Map<String, Object> payload) {
        requireGroup(groupId);
        List<Map<String, Object>> issues = validatePayload(payload).stream().map(this::issueToMap).toList();
        return Map.of("valid", issues.isEmpty(), "message", issues.isEmpty() ? "校验通过" : "校验失败", "issues", issues);
    }

    public Map<String, Object> testDraft(Long groupId, Map<String, Object> payload) {
        requireGroup(groupId);
        List<ApiSchemaValidationIssue> validationIssues = validatePayload(payload);
        if (!validationIssues.isEmpty()) {
            return testResult(false, "schema", null, null, validationIssues.get(0).message(), null, null);
        }
        String requestUrl = requiredString(payload, "requestUrl");
        String method = normalizeMethod(requiredString(payload, "requestMethod"));
        Map<String, Object> urlVariables = asObject(payload.get("urlVariables"));
        for (String variable : urlTemplateResolver.extractVariables(requestUrl)) {
            if (blank(stringValue(urlVariables.get(variable)))) {
                return testResult(false, "request", null, null, "URL 变量未填写: " + variable, null, null);
            }
        }
        Map<String, Object> body = asObject(payload.get("body"));
        ApiSchemaValidationResult bodyResult = schemaValidator.validatePayload(resolveInputSchema(payload), body, "请求体");
        if (!bodyResult.valid()) {
            return testResult(false, "request", null, null, bodyResult.issues().get(0).message(), null, null);
        }
        String resolvedUrl;
        try {
            resolvedUrl = urlTemplateResolver.resolve(requestUrl, urlVariables);
            requestSafetyValidator.validateRequestUrl(resolvedUrl);
        } catch (IllegalArgumentException exception) {
            return testResult(false, "request", null, null, exception.getMessage(), null, null);
        }
        long startedAt = System.currentTimeMillis();
        Map<String, Object> result;
        try {
            ApiAuthConfigService.EffectiveAuth effectiveAuth = authConfigService.resolveDraftEffectiveAuth(groupId, optionalLong(payload.get("id")), payload);
            ApiAuthResolver.AuthAppliedRequest appliedRequest = authResolver.apply(resolvedUrl, parseHeaders(payload.get("headers")), effectiveAuth);
            HttpRequest request = buildRequest(appliedRequest.url(), method, appliedRequest.headers(), body);
            HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build()
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (effectiveAuth.authType() == ApiAuthType.DIGEST && response.statusCode() == 401) {
                String challenge = response.headers().firstValue("WWW-Authenticate").orElse(null);
                if (digestAuthService.isDigestChallenge(challenge)) {
                    Map<String, String> digestHeaders = new LinkedHashMap<>(appliedRequest.headers());
                    digestHeaders.put(HttpHeaders.AUTHORIZATION, digestAuthService.buildAuthorization(challenge, method, URI.create(appliedRequest.url()), effectiveAuth.config()));
                    response = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build()
                            .send(buildRequest(appliedRequest.url(), method, digestHeaders, body), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                }
            }
            long durationMs = System.currentTimeMillis() - startedAt;
            boolean httpSuccess = response.statusCode() >= 200 && response.statusCode() < 300;
            if (response.body() != null && response.body().length() > MAX_RESPONSE_CHARS) {
                result = testResult(false, "request", response.statusCode(), null, "响应体过大，已超过限制", durationMs, null);
            } else if (!httpSuccess) {
                result = testResult(false, "request", response.statusCode(), response.body(), "HTTP 状态码不成功: " + response.statusCode(), durationMs, null);
            } else {
                Object responseBody = parseJsonOrEmpty(response.body());
                ApiSchemaValidationResult outputResult = schemaValidator.validatePayload(resolveOutputSchema(payload), responseBody, "响应体");
                result = testResult(outputResult.valid(), "request", response.statusCode(), response.body(), outputResult.valid() ? null : outputResult.issues().get(0).message(), durationMs, null);
            }
        } catch (Exception exception) {
            result = testResult(false, "request", null, null, exception.getMessage(), System.currentTimeMillis() - startedAt, null);
        }
        updateItemLastTestState(groupId, optionalLong(payload.get("id")), result);
        return result;
    }

    private void applyItemPayload(Long groupId, ApiItem item, Map<String, Object> payload) {
        List<ApiSchemaValidationIssue> issues = validatePayload(payload);
        if (!issues.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, issues.get(0).message());
        }
        item.setGroupId(groupId);
        item.setApiName(requiredString(payload, "apiName"));
        item.setDescription(optionalString(payload.get("description")));
        item.setEnabled(booleanValue(payload.get("enabled"), true));
        item.setRequestUrl(requiredString(payload, "requestUrl"));
        item.setRequestMethod(normalizeMethod(requiredString(payload, "requestMethod")));
        ApiAuthMode authMode = authConfigService.parseAuthMode(payload.get("authMode"));
        if (authMode == ApiAuthMode.CUSTOM) {
            authConfigService.resolveDraftEffectiveAuth(groupId, item.getId(), payload);
        }
        item.setAuthMode(authMode.name());
        item.setHeadersCiphertext(headerCryptoService.encrypt(normalizeHeadersForStorage(payload.get("headers"))));
        item.setInputSchema(normalizeSchemaForStorage(payload.get("inputSchema")));
        item.setOutputSchema(normalizeSchemaForStorage(payload.get("outputSchema")));
        if (item.getCreatedAt() == null) {
            item.setCreatedAt(LocalDateTime.now());
        }
        item.setUpdatedAt(LocalDateTime.now());
    }

    private List<ApiSchemaValidationIssue> validatePayload(Map<String, Object> payload) {
        List<ApiSchemaValidationIssue> issues = new java.util.ArrayList<>();
        if (payload == null) {
            issues.add(new ApiSchemaValidationIssue("payload", "请求参数不能为空"));
            return issues;
        }
        if (blank(optionalString(payload.get("apiName")))) {
            issues.add(new ApiSchemaValidationIssue("apiName", "API名称不能为空"));
        }
        if (blank(optionalString(payload.get("requestUrl")))) {
            issues.add(new ApiSchemaValidationIssue("requestUrl", "请求URL不能为空"));
        }
        if (blank(optionalString(payload.get("requestMethod")))) {
            issues.add(new ApiSchemaValidationIssue("requestMethod", "请求方法不能为空"));
        }
        if (!blank(optionalString(payload.get("inputSchema")))) {
            addSchemaIssues(issues, validateSchemaSafely(resolveInputSchema(payload), "输入Schema"));
        }
        if (!blank(optionalString(payload.get("outputSchema")))) {
            addSchemaIssues(issues, validateSchemaSafely(resolveOutputSchema(payload), "输出Schema"));
        }
        Object headers = payload.get("headers");
        if (headers != null) {
            try {
                parseHeaders(headers);
            } catch (ResponseStatusException exception) {
                issues.add(new ApiSchemaValidationIssue("headers", firstNonBlank(exception.getReason(), "Header 格式不正确")));
            }
        }
        try {
            authConfigService.resolveDraftEffectiveAuth(optionalLong(payload.get("groupId")), optionalLong(payload.get("id")), payload);
        } catch (ResponseStatusException exception) {
            issues.add(new ApiSchemaValidationIssue("authConfig", firstNonBlank(exception.getReason(), "鉴权配置不正确")));
        }
        return issues;
    }

    private ApiSchemaValidationResult validateSchemaSafely(String schema, String fieldName) {
        try {
            return schemaValidator.validateSchema(schema, fieldName);
        } catch (IllegalArgumentException exception) {
            return new ApiSchemaValidationResult(false, "校验失败", List.of(new ApiSchemaValidationIssue(fieldName, exception.getMessage())));
        }
    }

    private void addSchemaIssues(List<ApiSchemaValidationIssue> issues, ApiSchemaValidationResult result) {
        if (!result.valid()) {
            issues.addAll(result.issues());
        }
    }

    private HttpRequest buildRequest(String url, String method, Map<String, String> headers, Map<String, Object> body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url)).timeout(REQUEST_TIMEOUT);
        headers.forEach(builder::header);
        if (!headers.containsKey(HttpHeaders.CONTENT_TYPE) && !body.isEmpty()) {
            builder.header(HttpHeaders.CONTENT_TYPE, "application/json");
        }
        if (body.isEmpty() || "GET".equals(method) || "DELETE".equals(method)) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
        }
        return builder.build();
    }

    private Map<String, Object> toGroupSummary(ApiGroup group) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", group.getId());
        result.put("groupName", group.getGroupName());
        result.put("description", group.getDescription());
        result.put("enabled", group.getEnabled());
        result.put("status", Boolean.TRUE.equals(group.getEnabled()) ? "ENABLED" : "DISABLED");
        result.put("apiCount", group.getId() == null ? 0 : itemRepository.countByGroupId(group.getId()));
        if (group.getId() != null) {
            Map<String, Object> authConfig = authConfigService.getAuthConfig(ApiAuthScopeType.GROUP, group.getId());
            result.put("authType", authConfig.get("authType"));
            result.put("authPreview", authConfig.get("authPreview"));
        } else {
            result.put("authType", ApiAuthType.NO_AUTH.name());
            result.put("authPreview", "No Auth");
        }
        result.put("updatedAt", group.getUpdatedAt());
        return result;
    }

    private Map<String, Object> toItemSummary(ApiItem item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", item.getId());
        result.put("groupId", item.getGroupId());
        result.put("apiName", item.getApiName());
        result.put("description", item.getDescription());
        result.put("enabled", item.getEnabled());
        result.put("status", Boolean.TRUE.equals(item.getEnabled()) ? "ENABLED" : "DISABLED");
        result.put("requestUrl", item.getRequestUrl());
        result.put("requestMethod", item.getRequestMethod());
        String authMode = firstNonBlank(item.getAuthMode(), ApiAuthMode.INHERIT.name());
        ApiAuthConfigService.EffectiveAuth effectiveAuth = authConfigService.resolveEffectiveAuth(item.getGroupId(), item.getId(), authMode);
        result.put("authMode", authMode);
        result.put("authType", effectiveAuth.authType().name());
        result.put("authPreview", effectiveAuth.preview());
        result.put("inputSchema", item.getInputSchema());
        result.put("outputSchema", item.getOutputSchema());
        result.put("lastTestStatus", item.getLastTestStatus());
        result.put("lastTestTime", item.getLastTestTime());
        result.put("lastTestErrorMessage", item.getLastTestErrorMessage());
        result.put("lastTestToken", item.getLastTestToken());
        result.put("updatedAt", item.getUpdatedAt());
        return result;
    }

    private Map<String, Object> toItemDetail(ApiItem item) {
        Map<String, Object> result = toItemSummary(item);
        result.put("headers", parseStoredHeaders(headerCryptoService.decrypt(item.getHeadersCiphertext())));
        result.put("authConfig", authConfigService.getAuthConfig(ApiAuthScopeType.ITEM, item.getId()));
        result.put("urlVariables", urlTemplateResolver.extractVariables(item.getRequestUrl()));
        return result;
    }

    private void updateItemLastTestState(Long groupId, Long apiId, Map<String, Object> result) {
        if (apiId == null || apiId == 0L) {
            return;
        }
        ApiItem item = requireItem(groupId, apiId);
        boolean success = Boolean.TRUE.equals(result.get("success"));
        item.setLastTestStatus(success ? "SUCCESS" : "FAILED");
        item.setLastTestTime(LocalDateTime.now());
        item.setLastTestErrorMessage(success ? null : optionalString(result.get("errorMessage")));
        item.setLastTestToken(success ? optionalString(result.get("lastTestToken")) : null);
        item.setUpdatedAt(LocalDateTime.now());
        itemRepository.save(item);
    }

    private Map<String, Object> testResult(boolean success, String testType, Integer statusCode, String responsePayload, String errorMessage, Long durationMs, String token) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("testType", testType);
        result.put("statusCode", statusCode);
        result.put("responsePayload", responsePayload);
        result.put("errorMessage", errorMessage);
        result.put("durationMs", durationMs);
        result.put("testedAt", LocalDateTime.now());
        result.put("lastTestToken", token);
        return result;
    }

    private ApiGroup requireGroup(Long groupId) {
        return groupRepository.findById(groupId).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "API组不存在: " + groupId));
    }

    private ApiItem requireItem(Long groupId, Long apiId) {
        ApiItem item = itemRepository.findById(apiId).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "API不存在: " + apiId));
        if (!groupId.equals(item.getGroupId())) {
            throw new ResponseStatusException(NOT_FOUND, "API不属于当前API组");
        }
        return item;
    }

    private String normalizeSchemaForStorage(Object value) {
        String schema = optionalString(value);
        return schema == null ? "" : schema.trim();
    }

    private String resolveInputSchema(Map<String, Object> payload) {
        return firstNonBlank(optionalString(payload.get("inputSchema")), EMPTY_OBJECT_SCHEMA);
    }

    private String resolveOutputSchema(Map<String, Object> payload) {
        return firstNonBlank(optionalString(payload.get("outputSchema")), EMPTY_OBJECT_SCHEMA);
    }

    private String normalizeMethod(String value) {
        String method = value.trim().toUpperCase(Locale.ROOT);
        if (!List.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method)) {
            throw new ResponseStatusException(BAD_REQUEST, "不支持的请求方法: " + value);
        }
        return method;
    }

    private Map<String, String> parseHeaders(Object headersValue) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (headersValue == null) {
            return headers;
        }
        try {
            JsonNode root = objectMapper.valueToTree(headersValue);
            if (root.isTextual() && root.asText().isBlank()) {
                return headers;
            }
            if (root.isArray()) {
                for (JsonNode item : root) {
                    if (!item.isObject()) {
                        throw new IllegalArgumentException("Header 数组项必须是对象");
                    }
                    JsonNode enabledNode = firstField(item, "enabled", "checked", "selected");
                    if (enabledNode != null && enabledNode.isBoolean() && !enabledNode.asBoolean()) {
                        continue;
                    }
                    JsonNode keyNode = firstField(item, "key", "name", "headerName");
                    JsonNode valueNode = firstField(item, "value", "headerValue");
                    putHeader(headers, keyNode == null ? null : keyNode.asText(), valueNode);
                }
                return headers;
            }
            throw new IllegalArgumentException("Header 必须是 Postman 数组格式");
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(BAD_REQUEST, exception.getMessage());
        } catch (Exception exception) {
            throw new ResponseStatusException(BAD_REQUEST, "Header 必须是 Postman 数组格式");
        }
    }

    private String normalizeHeadersForStorage(Object headersValue) {
        if (headersValue == null) {
            return null;
        }
        parseHeaders(headersValue);
        try {
            JsonNode root = objectMapper.valueToTree(headersValue);
            if (!root.isArray()) {
                throw new IllegalArgumentException("Header 必须是 Postman 数组格式");
            }
            return objectMapper.writeValueAsString(headersValue);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(BAD_REQUEST, exception.getMessage());
        } catch (Exception exception) {
            throw new ResponseStatusException(BAD_REQUEST, "Header 必须是 Postman 数组格式");
        }
    }

    private Object parseStoredHeaders(String headersText) {
        if (headersText == null || headersText.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(headersText);
            return root.isArray() ? objectMapper.convertValue(root, Object.class) : List.of();
        } catch (Exception exception) {
            return List.of();
        }
    }

    private JsonNode firstField(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private void putHeader(Map<String, String> headers, String key, JsonNode valueNode) {
        if (key == null || key.isBlank() || valueNode == null || valueNode.isNull()) {
            return;
        }
        headers.put(key, valueNode.isValueNode() ? valueNode.asText() : valueNode.toString());
    }

    private Object parseJsonOrEmpty(String value) throws Exception {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(value, Object.class);
    }

    private String requiredString(Map<String, Object> payload, String key) {
        String value = optionalString(payload == null ? null : payload.get(key));
        if (value == null) {
            throw new ResponseStatusException(BAD_REQUEST, key + " 不能为空");
        }
        return value;
    }

    private String optionalString(Object value) {
        String text = stringValue(value);
        return text == null || text.isBlank() ? null : text.trim();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long optionalLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String firstNonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
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

    private Map<String, Object> issueToMap(ApiSchemaValidationIssue issue) {
        return Map.of("field", issue.field(), "message", issue.message());
    }

}
