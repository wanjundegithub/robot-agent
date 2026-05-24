package robot.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import robot.agent.config.WorkflowPromptProperties;
import robot.agent.dto.response.WorkflowVersionResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WelcomeBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(WelcomeBootstrapService.class);
    private static final Duration PYTHON_TIMEOUT = Duration.ofSeconds(5);

    private final ObjectMapper objectMapper;
    private final WorkflowService workflowService;
    private final ModelConfigService modelConfigService;
    private final PythonClient pythonClient;
    private final WebSocketPublisher webSocketPublisher;
    private final WorkflowPromptProperties workflowPromptProperties;
    private final Set<String> bootstrapKeys = ConcurrentHashMap.newKeySet();

    @Autowired
    public WelcomeBootstrapService(
            ObjectMapper objectMapper,
            WorkflowService workflowService,
            ModelConfigService modelConfigService,
            PythonClient pythonClient,
            WebSocketPublisher webSocketPublisher,
            WorkflowPromptProperties workflowPromptProperties
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.workflowService = Objects.requireNonNull(workflowService, "workflowService");
        this.modelConfigService = Objects.requireNonNull(modelConfigService, "modelConfigService");
        this.pythonClient = Objects.requireNonNull(pythonClient, "pythonClient");
        this.webSocketPublisher = Objects.requireNonNull(webSocketPublisher, "webSocketPublisher");
        this.workflowPromptProperties = Objects.requireNonNull(workflowPromptProperties, "workflowPromptProperties");
    }

    public WelcomeBootstrapService(
            ObjectMapper objectMapper,
            WorkflowService workflowService,
            ModelConfigService modelConfigService,
            PythonClient pythonClient,
            WebSocketPublisher webSocketPublisher
    ) {
        this(
                objectMapper,
                workflowService,
                modelConfigService,
                pythonClient,
                webSocketPublisher,
                new WorkflowPromptProperties()
        );
    }

    public void bootstrap(String sessionId, String workflowCode, String workflowVersion) {
        bootstrap(null, sessionId, workflowCode, workflowVersion);
    }

    public void bootstrap(String connectionId, String sessionId, String workflowCode, String workflowVersion) {
        if (isBlank(sessionId) || isBlank(workflowCode) || isBlank(workflowVersion)) {
            log.info(
                    "welcome.bootstrap.skip connectionId={} sessionId={} reason=missing_binding hasSession={} hasWorkflowCode={} hasWorkflowVersion={}",
                    connectionId,
                    sessionId,
                    !isBlank(sessionId),
                    !isBlank(workflowCode),
                    !isBlank(workflowVersion)
            );
            return;
        }

        log.info(
                "welcome.bootstrap.request connectionId={} sessionId={} hasWorkflowBinding=true",
                connectionId,
                sessionId
        );

        String bootstrapKey = bootstrapKey(sessionId, workflowCode, workflowVersion);
        if (!bootstrapKeys.add(bootstrapKey)) {
            log.info(
                    "welcome.bootstrap.skip connectionId={} sessionId={} reason=duplicate",
                    connectionId,
                    sessionId
            );
            return;
        }

        try {
            workflowService.requirePublishedWorkflowVersion(workflowCode, workflowVersion);
        } catch (RuntimeException exception) {
            log.info(
                    "welcome.bootstrap.skip connectionId={} sessionId={} reason=workflow_not_published message={}",
                    connectionId,
                    sessionId,
                    exception.getMessage()
            );
            bootstrapKeys.remove(bootstrapKey);
            return;
        }

        try {
            WorkflowVersionResponse workflowVersionResponse = workflowService.getWorkflowVersion(workflowCode, workflowVersion);
            WorkflowService.RuntimeExecutionBundle runtimeBundle = workflowService.buildRuntimeExecutionBundle(workflowCode, workflowVersion);
            ModelConfigService.RuntimeModelBundle welcomeModelBundle = resolveWelcomeModelBundle(runtimeBundle);
            if (welcomeModelBundle.providerConfigs().isEmpty() || welcomeModelBundle.modelRecords().isEmpty()) {
                log.info(
                        "welcome.bootstrap.skip connectionId={} sessionId={} reason=model_config_missing providerCount={} modelRecordCount={}",
                        connectionId,
                        sessionId,
                        welcomeModelBundle.providerConfigs().size(),
                        welcomeModelBundle.modelRecords().size()
                );
                boolean fallbackPublished = publishFallbackOpeningMessage(
                        connectionId,
                        sessionId,
                        workflowCode,
                        workflowVersion,
                        workflowVersionResponse,
                        runtimeBundle
                );
                if (!fallbackPublished) {
                    bootstrapKeys.remove(bootstrapKey);
                }
                return;
            }

            Map<String, Object> request = buildRequest(
                    sessionId,
                    workflowCode,
                    workflowVersion,
                    workflowVersionResponse,
                    runtimeBundle,
                    welcomeModelBundle
            );
            Map<String, Object> response = pythonClient.decideWorkflowWelcome(request).block(PYTHON_TIMEOUT);
            boolean shouldGreet = response != null && booleanValue(response.get("should_greet"));
            String message = response == null ? null : stringValue(response.get("message"));
            String reason = response == null ? null : stringValue(response.get("reason"));
            log.info(
                    "welcome.bootstrap.model.result connectionId={} sessionId={} shouldGreet={} messageLength={} reason={}",
                    connectionId,
                    sessionId,
                    shouldGreet,
                    message == null ? 0 : message.length(),
                    reason
            );
            if (shouldGreet && !isBlank(message)) {
                String executionId = welcomeExecutionId(sessionId, workflowCode, workflowVersion);
                log.info(
                        "welcome.bootstrap.publish connectionId={} sessionId={} executionId={} contentLength={}",
                        connectionId,
                        sessionId,
                        executionId,
                        message.length()
                );
                webSocketPublisher.publishMessageDelta(executionId, sessionId, message, true);
            }
        } catch (Exception exception) {
            log.warn(
                    "welcome.bootstrap.failed connectionId={} sessionId={} workflowCode={} workflowVersion={} message={}",
                    connectionId,
                    sessionId,
                    workflowCode,
                    workflowVersion,
                    exception.getMessage(),
                    exception
            );
            boolean fallbackPublished = publishFallbackOpeningMessage(connectionId, sessionId, workflowCode, workflowVersion);
            if (!fallbackPublished) {
                bootstrapKeys.remove(bootstrapKey);
            }
        }
    }

    private boolean publishFallbackOpeningMessage(
            String connectionId,
            String sessionId,
            String workflowCode,
            String workflowVersion
    ) {
        try {
            WorkflowVersionResponse workflowVersionResponse = workflowService.getWorkflowVersion(workflowCode, workflowVersion);
            WorkflowService.RuntimeExecutionBundle runtimeBundle = workflowService.buildRuntimeExecutionBundle(workflowCode, workflowVersion);
            return publishFallbackOpeningMessage(
                    connectionId,
                    sessionId,
                    workflowCode,
                    workflowVersion,
                    workflowVersionResponse,
                    runtimeBundle
            );
        } catch (Exception fallbackException) {
            log.info(
                    "welcome.bootstrap.fallback.skip connectionId={} sessionId={} reason=fallback_unavailable message={}",
                    connectionId,
                    sessionId,
                    fallbackException.getMessage()
            );
            return false;
        }
    }

    private boolean publishFallbackOpeningMessage(
            String connectionId,
            String sessionId,
            String workflowCode,
            String workflowVersion,
            WorkflowVersionResponse workflowVersionResponse,
            WorkflowService.RuntimeExecutionBundle runtimeBundle
    ) {
        String fallbackMessage = resolveFallbackOpeningMessage(workflowVersionResponse, runtimeBundle.workflowDefinition());
        if (isBlank(fallbackMessage)) {
            log.info(
                    "welcome.bootstrap.fallback.skip connectionId={} sessionId={} reason=no_opening_message",
                    connectionId,
                    sessionId
            );
            return false;
        }
        String executionId = welcomeExecutionId(sessionId, workflowCode, workflowVersion);
        log.info(
                "welcome.bootstrap.fallback.publish connectionId={} sessionId={} executionId={} contentLength={}",
                connectionId,
                sessionId,
                executionId,
                fallbackMessage.length()
        );
        webSocketPublisher.publishMessageDelta(executionId, sessionId, fallbackMessage, true);
        return true;
    }

    private String resolveFallbackOpeningMessage(
            WorkflowVersionResponse workflowVersionResponse,
            Map<String, Object> workflowDefinition
    ) {
        for (Map<String, Object> item : collectOpeningMessages(workflowDefinition)) {
            String messageText = stringValue(item.get("message_text"));
            if (!isBlank(messageText)) {
                return messageText;
            }
        }
        String workflowName = firstNonBlank(
                workflowVersionResponse == null ? null : workflowVersionResponse.getWorkflowName(),
                workflowVersionResponse == null ? null : workflowVersionResponse.getWorkflowCode(),
                "当前工作流"
        );
        return "您好，已进入「" + workflowName + "」流程。请告诉我您需要什么服务。";
    }

    private Map<String, Object> buildRequest(
            String sessionId,
            String workflowCode,
            String workflowVersion,
            WorkflowVersionResponse workflowVersionResponse,
            WorkflowService.RuntimeExecutionBundle runtimeBundle,
            ModelConfigService.RuntimeModelBundle welcomeModelBundle
    ) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("session_id", sessionId);
        request.put("workflow_code", workflowCode);
        request.put("workflow_version", workflowVersion);
        request.put("workflow_summary", buildWorkflowSummary(
                workflowVersionResponse,
                runtimeBundle.workflowDefinition(),
                runtimeBundle.entryRule()
        ));
        request.put("session_context", Map.of(
                "trigger", "ws_bootstrap",
                "has_user_message", false
        ));
        request.put("provider_configs", welcomeModelBundle.providerConfigs());
        request.put("model_records", welcomeModelBundle.modelRecords());
        request.put("routing_model_code", resolveWelcomeModelCode(runtimeBundle, welcomeModelBundle));
        request.put("system_prompts", workflowPromptProperties.asWorkflowConfigSystemPrompts());
        return request;
    }

    private ModelConfigService.RuntimeModelBundle resolveWelcomeModelBundle(WorkflowService.RuntimeExecutionBundle runtimeBundle) {
        if (!runtimeBundle.providerConfigs().isEmpty() && !runtimeBundle.modelRecords().isEmpty()) {
            return new ModelConfigService.RuntimeModelBundle(runtimeBundle.providerConfigs(), runtimeBundle.modelRecords());
        }
        log.info(
                "welcome.bootstrap.model.fallback reason=workflow_model_missing providerCount={} modelRecordCount={}",
                runtimeBundle.providerConfigs().size(),
                runtimeBundle.modelRecords().size()
        );
        return modelConfigService.buildDefaultRuntimeBundle();
    }

    private String resolveWelcomeModelCode(
            WorkflowService.RuntimeExecutionBundle runtimeBundle,
            ModelConfigService.RuntimeModelBundle welcomeModelBundle
    ) {
        String workflowRoutingModel = firstNonBlank(runtimeBundle.routingModelCode());
        if (workflowRoutingModel != null && containsModelCode(welcomeModelBundle.modelRecords(), workflowRoutingModel)) {
            return workflowRoutingModel;
        }
        if (!welcomeModelBundle.modelRecords().isEmpty()) {
            String modelCode = stringValue(welcomeModelBundle.modelRecords().get(0).get("model_code"));
            if (modelCode != null) {
                return modelCode;
            }
        }
        return workflowRoutingModel;
    }

    private boolean containsModelCode(List<Map<String, Object>> modelRecords, String modelCode) {
        if (isBlank(modelCode)) {
            return false;
        }
        for (Map<String, Object> modelRecord : modelRecords) {
            if (modelCode.equals(stringValue(modelRecord.get("model_code")))) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> buildWorkflowSummary(
            WorkflowVersionResponse workflowVersionResponse,
            Map<String, Object> workflowDefinition,
            Map<String, Object> entryRule
    ) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", safeText(workflowVersionResponse.getWorkflowName()));
        summary.put("description", safeText(workflowVersionResponse.getWorkflowDescription()));
        summary.put("entry_rule", copyMap(entryRule));
        summary.put("coordinator_prompts", collectCoordinatorPrompts(workflowDefinition));
        summary.put("opening_messages", collectOpeningMessages(workflowDefinition));
        return summary;
    }

    private List<Map<String, Object>> collectCoordinatorPrompts(Map<String, Object> workflowDefinition) {
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> nodes = resolveMainNodes(workflowDefinition);
        for (Map.Entry<String, Object> entry : nodes.entrySet()) {
            Map<String, Object> node = asMap(entry.getValue());
            if (!"coordinator".equalsIgnoreCase(stringValue(node.get("type")))) {
                continue;
            }
            Map<String, Object> config = asMap(node.get("config"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("node_id", firstNonBlank(stringValue(node.get("id")), entry.getKey()));
            putIfPresent(item, "description", firstNonBlank(stringValue(node.get("description")), stringValue(config.get("description"))));
            putIfPresent(item, "prompt", firstNonBlank(stringValue(config.get("prompt")), stringValue(node.get("prompt"))));
            putIfPresent(item, "user_prompt", firstNonBlank(stringValue(config.get("user_prompt")), stringValue(node.get("user_prompt"))));
            if (!item.isEmpty()) {
                items.add(item);
            }
        }
        return items;
    }

    private List<Map<String, Object>> collectOpeningMessages(Map<String, Object> workflowDefinition) {
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> nodes = resolveMainNodes(workflowDefinition);
        for (Map.Entry<String, Object> entry : nodes.entrySet()) {
            Map<String, Object> node = asMap(entry.getValue());
            if (!"message".equalsIgnoreCase(stringValue(node.get("type")))) {
                continue;
            }
            Map<String, Object> config = asMap(node.get("config"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("node_id", firstNonBlank(stringValue(node.get("id")), entry.getKey()));
            putIfPresent(item, "description", firstNonBlank(stringValue(node.get("description")), stringValue(config.get("description"))));
            putIfPresent(item, "message_text", firstNonBlank(stringValue(config.get("message_text")), stringValue(node.get("message_text"))));
            if (!item.isEmpty()) {
                items.add(item);
            }
        }
        return items;
    }

    private Map<String, Object> resolveMainNodes(Map<String, Object> workflowDefinition) {
        Map<String, Object> graphs = asMap(workflowDefinition.get("graphs"));
        if (!graphs.isEmpty()) {
            String mainGraphId = firstNonBlank(stringValue(workflowDefinition.get("main_graph_id")));
            if (mainGraphId != null) {
                Map<String, Object> graph = asMap(graphs.get(mainGraphId));
                Map<String, Object> nodes = asMap(graph.get("nodes"));
                if (!nodes.isEmpty()) {
                    return nodes;
                }
            }
            for (Object graphValue : graphs.values()) {
                Map<String, Object> graph = asMap(graphValue);
                if ("main".equalsIgnoreCase(stringValue(graph.get("graph_type")))) {
                    Map<String, Object> nodes = asMap(graph.get("nodes"));
                    if (!nodes.isEmpty()) {
                        return nodes;
                    }
                }
            }
        }
        return asMap(workflowDefinition.get("nodes"));
    }

    private void putIfPresent(Map<String, Object> target, String key, String value) {
        if (!isBlank(value)) {
            target.put(key, value);
        }
    }

    private Map<String, Object> copyMap(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(value, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    private String bootstrapKey(String sessionId, String workflowCode, String workflowVersion) {
        return String.join("|", sessionId, workflowCode, workflowVersion, "ws_bootstrap");
    }

    private String welcomeExecutionId(String sessionId, String workflowCode, String workflowVersion) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bootstrapKey(sessionId, workflowCode, workflowVersion).getBytes(StandardCharsets.UTF_8));
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
            String suffix = encoded.length() > 12 ? encoded.substring(0, 12) : encoded;
            return "welcome_" + sessionId + "_" + suffix;
        } catch (Exception exception) {
            return "welcome_" + sessionId + "_" + Math.abs(bootstrapKey(sessionId, workflowCode, workflowVersion).hashCode());
        }
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value == null) {
            return false;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return result;
        }
        return new LinkedHashMap<>();
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
