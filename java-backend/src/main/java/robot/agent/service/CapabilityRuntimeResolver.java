package robot.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import robot.agent.model.CapabilityGroupSnapshot;
import robot.agent.model.CapabilityItem;
import robot.agent.repository.CapabilityGroupRepository;
import robot.agent.repository.CapabilityGroupSnapshotRepository;
import robot.agent.repository.CapabilityItemRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class CapabilityRuntimeResolver {

    private final CapabilityItemRepository capabilityItemRepository;
    private final CapabilityGroupRepository capabilityGroupRepository;
    private final CapabilityGroupSnapshotRepository capabilityGroupSnapshotRepository;
    private final CapabilityService capabilityService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CapabilityRuntimeResolver(
            CapabilityItemRepository capabilityItemRepository,
            CapabilityGroupRepository capabilityGroupRepository,
            CapabilityGroupSnapshotRepository capabilityGroupSnapshotRepository,
            CapabilityService capabilityService
    ) {
        this.capabilityItemRepository = capabilityItemRepository;
        this.capabilityGroupRepository = capabilityGroupRepository;
        this.capabilityGroupSnapshotRepository = capabilityGroupSnapshotRepository;
        this.capabilityService = capabilityService;
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
            if (!"tool".equals(stringValue(node.get("type")))) {
                continue;
            }
            Map<String, Object> config = asMap(node.get("config"));
            if (!"capability".equalsIgnoreCase(stringValue(config.get("invoke_type")))) {
                continue;
            }

            Map<String, Object> nextNode = new LinkedHashMap<>(node);
            nextNode.put("config", resolveCapabilityConfig(config));
            resolvedNodes.put(entry.getKey(), nextNode);
        }
        return resolvedNodes;
    }

    private Map<String, Object> resolveCapabilityConfig(Map<String, Object> config) {
        GroupBinding groupBinding = resolveGroupBinding(config);
        String capabilityCode = firstNonBlank(stringValue(config.get("capability_code")), stringValue(config.get("tool_code")));
        if (capabilityCode == null) {
            throw new ResponseStatusException(BAD_REQUEST, "capability_code 不能为空");
        }

        String groupSnapshotVersion = stringValue(config.get("group_snapshot_version"));
        String requestedCapabilityVersion = stringValue(config.get("capability_version"));
        ResolvedCapability resolvedCapability = resolveCapabilityVersionAndDefinition(
                groupBinding.groupCode(),
                capabilityCode,
                groupSnapshotVersion,
                requestedCapabilityVersion
        );

        Map<String, Object> resolved = new LinkedHashMap<>();
        resolved.put("tool_code", capabilityCode);
        resolved.put("invoke_type", "api");
        resolved.put("group_id", groupBinding.groupId());
        resolved.put("group_code", groupBinding.groupCode());
        resolved.put("capability_code", capabilityCode);
        resolved.put("capability_version", resolvedCapability.capabilityVersion());
        if (groupSnapshotVersion != null) {
            resolved.put("group_snapshot_version", groupSnapshotVersion);
        }
        resolved.putAll(parseDefinition(resolvedCapability.definitionJson()));
        resolved.put("headers", capabilityService.buildResolvedHeadersForCapability(groupBinding.groupId(), capabilityCode));

        Object payloadMapping = config.get("payload_mapping");
        if (payloadMapping != null) {
            resolved.put("payload_mapping", payloadMapping);
        }
        if (config.get("timeout") != null) {
            resolved.put("timeout", config.get("timeout"));
        }
        if (config.get("retry_policy") != null) {
            resolved.put("retry_policy", config.get("retry_policy"));
        }
        if (config.get("idempotent") != null) {
            resolved.put("idempotent", config.get("idempotent"));
        }
        if (resolved.get("method") == null) {
            resolved.put("method", "POST");
        }
        return resolved;
    }

    private GroupBinding resolveGroupBinding(Map<String, Object> config) {
        Long groupId = optionalLong(config.get("group_id"));
        String configuredGroupCode = stringValue(config.get("group_code"));
        String groupCodeFromId = groupId == null ? null : capabilityService.resolveGroupCode(groupId);

        if (groupId == null && configuredGroupCode != null) {
            groupId = capabilityGroupRepository.findByGroupCode(configuredGroupCode)
                    .map(group -> group.getId())
                    .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "group_code 不存在: " + configuredGroupCode));
            groupCodeFromId = configuredGroupCode;
        }

        if (configuredGroupCode != null && groupCodeFromId != null && !configuredGroupCode.equals(groupCodeFromId)) {
            throw new ResponseStatusException(BAD_REQUEST, "group_id 与 group_code 不一致");
        }
        String effectiveGroupCode = firstNonBlank(configuredGroupCode, groupCodeFromId);
        if (groupId == null || effectiveGroupCode == null) {
            throw new ResponseStatusException(BAD_REQUEST, "group_id 不能为空");
        }
        return new GroupBinding(groupId, effectiveGroupCode);
    }

    private ResolvedCapability resolveCapabilityVersionAndDefinition(
            String groupCode,
            String capabilityCode,
            String groupSnapshotVersion,
            String requestedCapabilityVersion
    ) {
        if (groupSnapshotVersion != null) {
            return resolveFromSnapshot(groupCode, capabilityCode, groupSnapshotVersion, requestedCapabilityVersion);
        }

        CapabilityItem item = capabilityItemRepository.findByGroupCodeAndCapabilityCode(groupCode, capabilityCode)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "能力项不存在: " + capabilityCode));
        if (item.getCapabilityType() == null || !"API".equals(item.getCapabilityType().name().toUpperCase(Locale.ROOT))) {
            throw new ResponseStatusException(BAD_REQUEST, "工具节点当前仅支持 API 能力");
        }
        if (item.getPublishedVersion() == null || item.getPublishedVersion().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "能力项尚未发布: " + capabilityCode);
        }
        if (requestedCapabilityVersion != null && !requestedCapabilityVersion.equals(item.getPublishedVersion())) {
            throw new ResponseStatusException(BAD_REQUEST, "capability_version 与已发布版本不匹配: " + requestedCapabilityVersion);
        }
        String resolvedVersion = firstNonBlank(requestedCapabilityVersion, item.getPublishedVersion());
        return new ResolvedCapability(resolvedVersion, item.getDefinitionJson());
    }

    private ResolvedCapability resolveFromSnapshot(
            String groupCode,
            String capabilityCode,
            String groupSnapshotVersion,
            String requestedCapabilityVersion
    ) {
        CapabilityGroupSnapshot snapshot = capabilityGroupSnapshotRepository.findByGroupCodeAndSnapshotVersion(groupCode, groupSnapshotVersion)
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "group_snapshot_version 不存在: " + groupSnapshotVersion));
        Map<String, Object> snapshotPayload = parseDefinition(snapshot.getSnapshotPayload());
        List<Map<String, Object>> capabilities = asListOfMaps(snapshotPayload.get("capabilities"));
        Map<String, Object> capabilitySnapshot = capabilities.stream()
                .filter(capability -> capabilityCode.equals(firstNonBlank(
                        stringValue(capability.get("capabilityCode")),
                        stringValue(capability.get("capability_code"))
                )))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "快照中不存在能力: " + capabilityCode));

        String capabilityType = firstNonBlank(
                stringValue(capabilitySnapshot.get("capabilityType")),
                stringValue(capabilitySnapshot.get("capability_type"))
        );
        if (capabilityType != null && !"API".equalsIgnoreCase(capabilityType)) {
            throw new ResponseStatusException(BAD_REQUEST, "工具节点当前仅支持 API 能力");
        }

        String snapshotCapabilityVersion = firstNonBlank(
                stringValue(capabilitySnapshot.get("version")),
                stringValue(capabilitySnapshot.get("capability_version"))
        );
        if (snapshotCapabilityVersion == null) {
            throw new ResponseStatusException(BAD_REQUEST, "快照缺少 capability version: " + capabilityCode);
        }
        if (requestedCapabilityVersion != null && !requestedCapabilityVersion.equals(snapshotCapabilityVersion)) {
            throw new ResponseStatusException(BAD_REQUEST, "capability_version 与快照版本不匹配: " + requestedCapabilityVersion);
        }

        String definitionJson = firstNonBlank(
                stringValue(capabilitySnapshot.get("definitionJson")),
                stringValue(capabilitySnapshot.get("definition_json"))
        );
        if (definitionJson == null) {
            throw new ResponseStatusException(BAD_REQUEST, "快照缺少能力定义: " + capabilityCode);
        }
        return new ResolvedCapability(snapshotCapabilityVersion, definitionJson);
    }

    private Map<String, Object> parseDefinition(String rawDefinition) {
        if (rawDefinition == null || rawDefinition.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(rawDefinition, new TypeReference<Map<String, Object>>() {});
        } catch (Exception exception) {
            throw new ResponseStatusException(BAD_REQUEST, "能力定义 JSON 格式不正确");
        }
    }

    private List<Map<String, Object>> asListOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?>) {
                result.add(asMap(item));
            }
        }
        return result;
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

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private record GroupBinding(Long groupId, String groupCode) {
    }

    private record ResolvedCapability(String capabilityVersion, String definitionJson) {
    }
}
