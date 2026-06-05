package robot.agent.apicenter.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import robot.agent.apicenter.model.ApiAuthMode;
import robot.agent.apicenter.model.ApiItem;
import robot.agent.apicenter.repository.ApiGroupRepository;
import robot.agent.apicenter.repository.ApiItemRepository;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class ApiRuntimeResolver {

    private final ApiGroupRepository groupRepository;
    private final ApiItemRepository itemRepository;
    private final ApiHeaderCryptoService headerCryptoService;
    private final ApiAuthConfigService authConfigService;
    private final ApiAuthResolver authResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ApiRuntimeResolver(
            ApiGroupRepository groupRepository,
            ApiItemRepository itemRepository,
            ApiHeaderCryptoService headerCryptoService,
            ApiAuthConfigService authConfigService,
            ApiAuthResolver authResolver
    ) {
        this.groupRepository = groupRepository;
        this.itemRepository = itemRepository;
        this.headerCryptoService = headerCryptoService;
        this.authConfigService = authConfigService;
        this.authResolver = authResolver;
    }

    public Map<String, Object> resolveWorkflowDefinition(Map<String, Object> workflowDefinition) {
        Map<String, Object> resolvedDefinition = deepCopy(workflowDefinition);
        Map<String, Object> graphs = asMap(resolvedDefinition.get("graphs"));
        if (!graphs.isEmpty()) {
            for (Map.Entry<String, Object> graphEntry : graphs.entrySet()) {
                Map<String, Object> graph = asMap(graphEntry.getValue());
                graph.put("nodes", resolveNodes(asMap(graph.get("nodes"))));
                graphs.put(graphEntry.getKey(), graph);
            }
            resolvedDefinition.put("graphs", graphs);
            return resolvedDefinition;
        }
        resolvedDefinition.put("nodes", resolveNodes(asMap(resolvedDefinition.get("nodes"))));
        return resolvedDefinition;
    }

    private Map<String, Object> resolveNodes(Map<String, Object> nodes) {
        Map<String, Object> resolvedNodes = new LinkedHashMap<>(nodes);
        for (Map.Entry<String, Object> entry : nodes.entrySet()) {
            Map<String, Object> node = asMap(entry.getValue());
            String nodeType = stringValue(node.get("type"));
            if (!"tool".equals(nodeType) && !"api".equals(nodeType)) {
                continue;
            }
            Map<String, Object> config = asMap(node.get("config"));
            if (!"api".equalsIgnoreCase(stringValue(config.get("invoke_type")))) {
                continue;
            }
            Map<String, Object> nextNode = new LinkedHashMap<>(node);
            nextNode.put("config", resolveApiConfig(config));
            resolvedNodes.put(entry.getKey(), nextNode);
        }
        return resolvedNodes;
    }

    private Map<String, Object> resolveApiConfig(Map<String, Object> config) {
        Long groupId = optionalLong(config.get("group_id"));
        Long apiId = optionalLong(config.get("api_id"));
        if (groupId == null) {
            throw new ResponseStatusException(BAD_REQUEST, "group_id 不能为空");
        }
        if (apiId == null) {
            throw new ResponseStatusException(BAD_REQUEST, "api_id 不能为空");
        }
        groupRepository.findById(groupId).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "API组不存在: " + groupId));
        ApiItem item = itemRepository.findById(apiId).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "API不存在: " + apiId));
        if (!groupId.equals(item.getGroupId())) {
            throw new ResponseStatusException(BAD_REQUEST, "API不属于当前API组");
        }
        if (!Boolean.TRUE.equals(item.getEnabled())) {
            throw new ResponseStatusException(BAD_REQUEST, "API已停用: " + apiId);
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        resolved.put("tool_code", String.valueOf(apiId));
        resolved.put("invoke_type", "api");
        resolved.put("group_id", groupId);
        resolved.put("api_id", apiId);
        resolved.put("api_name", item.getApiName());
        String authMode = firstNonBlank(item.getAuthMode(), ApiAuthMode.INHERIT.name());
        ApiAuthConfigService.EffectiveAuth effectiveAuth = authConfigService.resolveEffectiveAuth(groupId, apiId, authMode);
        ApiAuthResolver.AuthAppliedRequest appliedRequest = authResolver.apply(item.getRequestUrl(), parseHeaderArray(headerCryptoService.decrypt(item.getHeadersCiphertext())), effectiveAuth);
        resolved.put("url", appliedRequest.url());
        resolved.put("method", item.getRequestMethod());
        resolved.put("headers", appliedRequest.headers());
        resolved.put("auth_mode", authMode);
        resolved.put("auth_type", effectiveAuth.authType().name());
        resolved.put("auth_preview", effectiveAuth.preview());
        resolved.put("input_schema", item.getInputSchema());
        resolved.put("output_schema", item.getOutputSchema());
        resolved.put("payload_mapping", asMap(config.get("payload_mapping")));
        resolved.put("output_mapping", asMap(config.get("output_mapping")));
        return resolved;
    }

    private Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception exception) {
            throw new ResponseStatusException(BAD_REQUEST, "API Header JSON 格式不正确");
        }
    }

    private Map<String, String> parseHeaderArray(String json) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return headers;
        }
        try {
            Object value = objectMapper.readValue(json, Object.class);
            if (value instanceof Iterable<?> iterable) {
                for (Object item : iterable) {
                    Map<String, Object> header = asMap(item);
                    if (Boolean.FALSE.equals(header.get("enabled")) || Boolean.FALSE.equals(header.get("checked")) || Boolean.FALSE.equals(header.get("selected"))) {
                        continue;
                    }
                    String key = firstNonBlank(stringValue(header.get("key")), firstNonBlank(stringValue(header.get("name")), stringValue(header.get("headerName"))));
                    String headerValue = firstNonBlank(stringValue(header.get("value")), stringValue(header.get("headerValue")));
                    if (key != null && !key.isBlank() && headerValue != null) {
                        headers.put(key, headerValue);
                    }
                }
            }
            return headers;
        } catch (Exception exception) {
            throw new ResponseStatusException(BAD_REQUEST, "API Header JSON 格式不正确");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopy(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(source, new TypeReference<Map<String, Object>>() {});
    }

    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    converted.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return converted;
        }
        return new LinkedHashMap<>();
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
            return null;
        }
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String firstNonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }
}
