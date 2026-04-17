package robot.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import robot.agent.model.*;
import robot.agent.repository.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Component
public class DemoWorkflowDataInitializer implements ApplicationRunner {

    private static final Long DEFAULT_WORKSPACE_ID = 1L;

    private final WorkflowRepository workflowRepository;
    private final WorkflowVersionRepository workflowVersionRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeVersionRepository knowledgeVersionRepository;
    private final LlmProviderConfigRepository llmProviderConfigRepository;
    private final LlmModelProfileRepository llmModelProfileRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final ObjectMapper objectMapper;

    public DemoWorkflowDataInitializer(
            WorkflowRepository workflowRepository,
            WorkflowVersionRepository workflowVersionRepository,
            KnowledgeBaseRepository knowledgeBaseRepository,
            KnowledgeVersionRepository knowledgeVersionRepository,
            LlmProviderConfigRepository llmProviderConfigRepository,
            LlmModelProfileRepository llmModelProfileRepository,
            RoleRepository roleRepository,
            UserRoleRepository userRoleRepository,
            ObjectMapper objectMapper
    ) {
        this.workflowRepository = workflowRepository;
        this.workflowVersionRepository = workflowVersionRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.knowledgeVersionRepository = knowledgeVersionRepository;
        this.llmProviderConfigRepository = llmProviderConfigRepository;
        this.llmModelProfileRepository = llmModelProfileRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        seedRoles();
        seedModelConfigs();
        seedKnowledgeBase();
        seedWorkflows();
    }

    private void seedRoles() {
        createRoleIfMissing("workflow_admin", "Workflow Admin", "Can manage workflow definitions and versions.");
        createRoleIfMissing("knowledge_admin", "Knowledge Admin", "Can manage knowledge bases and versions.");
        createRoleIfMissing("viewer", "Viewer", "Read-only access to workflow resources.");

        assignRoleIfMissing("demo-admin", DEFAULT_WORKSPACE_ID, "workflow_admin");
        assignRoleIfMissing("demo-admin", DEFAULT_WORKSPACE_ID, "knowledge_admin");
        assignRoleIfMissing("demo-admin", DEFAULT_WORKSPACE_ID, "viewer");
        assignRoleIfMissing("demo-user", DEFAULT_WORKSPACE_ID, "viewer");
    }

    private void createRoleIfMissing(String code, String name, String description) {
        if (roleRepository.findByCode(code).isPresent()) {
            return;
        }
        Role role = new Role();
        role.setCode(code);
        role.setName(name);
        role.setDescription(description);
        roleRepository.save(role);
    }

    private void assignRoleIfMissing(String userId, Long workspaceId, String roleCode) {
        Role role = roleRepository.findByCode(roleCode)
                .orElseThrow(() -> new IllegalStateException("Role not found: " + roleCode));
        UserRoleId userRoleId = new UserRoleId();
        userRoleId.setUserId(userId);
        userRoleId.setWorkspaceId(workspaceId);
        userRoleId.setRoleId(role.getId());
        if (userRoleRepository.existsById(userRoleId)) {
            return;
        }
        UserRole userRole = new UserRole();
        userRole.setId(userRoleId);
        userRole.setRole(role);
        userRoleRepository.save(userRole);
    }

    private void seedKnowledgeBase() {
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findByKbCode("flight_policy_kb")
                .orElseGet(() -> {
                    KnowledgeBase base = new KnowledgeBase();
                    base.setWorkspaceId(DEFAULT_WORKSPACE_ID);
                    base.setKbCode("flight_policy_kb");
                    base.setName("Flight Policy KB");
                    base.setDescription("Flight booking and change policy knowledge base.");
                    base.setEmbeddingModel("demo-embedding-model");
                    base.setStatus(KnowledgeBaseStatus.ACTIVE);
                    base.setCreatedBy("system");
                    base.setCreatedAt(LocalDateTime.now());
                    return knowledgeBaseRepository.save(base);
                });

        if (knowledgeVersionRepository.findByKbCodeAndVersion("flight_policy_kb", "1.0.0").isEmpty()) {
            KnowledgeVersion version = new KnowledgeVersion();
            version.setKbCode("flight_policy_kb");
            version.setVersion("1.0.0");
            version.setStatus(KnowledgeVersionStatus.PUBLISHED);
            version.setChunkCount(4);
            version.setDocCount(2);
            version.setCreatedBy("system");
            version.setCreatedAt(LocalDateTime.now());
            version.setPublishedAt(LocalDateTime.now());
            knowledgeVersionRepository.save(version);
        }

        knowledgeBase.setCurrentVersion("1.0.0");
        knowledgeBaseRepository.save(knowledgeBase);
    }

    private void seedModelConfigs() throws Exception {
        if (llmProviderConfigRepository.findByProviderCode("openai-compatible-prod").isEmpty()) {
            LlmProviderConfig provider = new LlmProviderConfig();
            provider.setProviderCode("openai-compatible-prod");
            provider.setProviderType("openai_compatible");
            provider.setBaseUrl("https://llm.example.com/v1");
            provider.setApiKeySecretRef("env:ROBOT_LLM_API_KEY");
            provider.setExtraHeaders(objectMapper.writeValueAsString(Map.of("x-tenant-id", "workspace_001")));
            provider.setCreatedBy("system");
            llmProviderConfigRepository.save(provider);
        }

        seedProfile("intent-router-v1", "openai-compatible-prod", "qwen-plus", "intent_routing", 0.10d, 0.80d, 512);
        seedProfile("knowledge-query-rewrite-v1", "openai-compatible-prod", "qwen-plus", "knowledge_query_rewrite", 0.10d, 0.90d, 512);
        seedProfile("knowledge-answer-v1", "openai-compatible-prod", "qwen-plus", "knowledge_answer", 0.20d, 0.90d, 1024);
        seedProfile("general-chat-v1", "openai-compatible-prod", "qwen-plus", "general_llm", 0.30d, 0.95d, 1024);
        seedProfile("general-chat-fallback-v1", "openai-compatible-prod", "qwen-turbo", "general_llm", 0.20d, 0.90d, 1024);
        seedProfile("structured-extraction-v1", "openai-compatible-prod", "qwen-plus", "structured_extraction", 0.10d, 0.80d, 512);
    }

    private void seedProfile(
            String profileCode,
            String providerCode,
            String modelCode,
            String purpose,
            double temperature,
            double topP,
            int maxTokens
    ) {
        if (llmModelProfileRepository.findByProfileCode(profileCode).isPresent()) {
            return;
        }
        LlmModelProfile profile = new LlmModelProfile();
        profile.setProfileCode(profileCode);
        profile.setProviderCode(providerCode);
        profile.setModelCode(modelCode);
        profile.setPurpose(purpose);
        profile.setTemperature(java.math.BigDecimal.valueOf(temperature));
        profile.setTopP(java.math.BigDecimal.valueOf(topP));
        profile.setMaxTokens(maxTokens);
        profile.setTimeoutSec(15);
        profile.setCreatedBy("system");
        llmModelProfileRepository.save(profile);
    }

    private void seedWorkflows() throws Exception {
        seedWorkflowDefinition("flight_booking", "Flight Booking", "Phase 2 flight booking workflow.", "2.0.0");
        seedWorkflowDefinition("hotel_booking", "Hotel Booking", "Phase 2 hotel booking workflow.", "1.0.0");
        seedWorkflowDefinition("general_query", "General Query", "Phase 2 knowledge-assisted general query workflow.", "1.0.0");

        seedWorkflowVersion("flight_booking", "1.0.0", flightBookingV1(), entryRule(List.of("flight", "ticket", "booking", "航班", "机票", "订票"), 100));
        seedWorkflowVersion("flight_booking", "2.0.0", flightBookingV2(), entryRule(List.of("flight", "ticket", "booking", "航班", "机票", "订票"), 120));
        seedWorkflowVersion("hotel_booking", "1.0.0", hotelBookingV1(), entryRule(List.of("hotel", "room", "住宿", "酒店"), 110));
        seedWorkflowVersion("general_query", "1.0.0", generalQueryV1(), entryRule(List.of("policy", "refund", "改签", "退票", "政策"), 90));
    }

    private void seedWorkflowDefinition(String workflowCode, String name, String description, String currentVersion) {
        Workflow workflow = workflowRepository.findByWorkflowCode(workflowCode)
                .orElseGet(() -> {
                    Workflow created = new Workflow();
                    created.setWorkspaceId(DEFAULT_WORKSPACE_ID);
                    created.setWorkflowCode(workflowCode);
                    created.setName(name);
                    created.setDescription(description);
                    created.setCreatedBy("system");
                    created.setCreatedAt(LocalDateTime.now());
                    return created;
                });

        workflow.setStatus(WorkflowStatus.PUBLISHED);
        workflow.setCurrentVersion(currentVersion);
        workflow.setUpdatedAt(LocalDateTime.now());
        workflowRepository.save(workflow);
    }

    private void seedWorkflowVersion(String workflowCode, String version, Map<String, Object> definition, Map<String, Object> entryRule) throws Exception {
        if (workflowVersionRepository.findByWorkflowCodeAndVersion(workflowCode, version).isPresent()) {
            return;
        }

        WorkflowVersion workflowVersion = new WorkflowVersion();
        workflowVersion.setWorkflowCode(workflowCode);
        workflowVersion.setVersion(version);
        workflowVersion.setStatus(WorkflowVersionStatus.PUBLISHED);
        workflowVersion.setDefinition(objectMapper.writeValueAsString(definition));
        workflowVersion.setEntryRule(objectMapper.writeValueAsString(entryRule));
        workflowVersion.setEditorMeta(objectMapper.writeValueAsString(Map.of(
                "layout_engine", "reactflow",
                "viewport", Map.of("x", 0, "y", 0, "zoom", 0.92),
                "readonly", false,
                "last_saved_by", "system"
        )));
        workflowVersion.setConfig(objectMapper.writeValueAsString(Map.of(
                "intent_profile_ref", "intent-router-v1",
                "llm_defaults", Map.of("model_profile_ref", "general-chat-v1", "provider_code", "openai-compatible-prod")
        )));
        workflowVersion.setCreatedBy("system");
        workflowVersion.setCreatedAt(LocalDateTime.now());
        workflowVersion.setPublishedAt(LocalDateTime.now());
        workflowVersionRepository.save(workflowVersion);
    }

    private Map<String, Object> entryRule(List<String> keywords, int priority) {
        return Map.of(
                "intent_codes", keywords,
                "keywords", keywords,
                "priority", priority
        );
    }

    private Map<String, Object> flightBookingV1() {
        return Map.of(
                "workflow_code", "flight_booking",
                "workflow_version", "1.0.0",
                "entry", "start",
                "nodes", Map.of(
                        "start", Map.of("id", "start", "type", "start"),
                        "extract_slots", Map.of("id", "extract_slots", "type", "llm", "config", Map.of("prompt", "slot_extraction", "model_profile_ref", "structured-extraction-v1")),
                        "check_slots", Map.of("id", "check_slots", "type", "condition", "config", Map.of("required_fields", List.of("departure_city", "arrival_city", "departure_date"))),
                        "collect_info", Map.of("id", "collect_info", "type", "form", "config", bookingForm("请补充出行信息", "还缺少部分订票信息，请补全后继续。")),
                        "end", Map.of("id", "end", "type", "end", "config", Map.of("output_format", Map.of(
                                "departure_city", "execution.departure_city",
                                "arrival_city", "execution.arrival_city",
                                "departure_date", "execution.departure_date",
                                "passengers", "execution.passengers"
                        )))
                ),
                "transitions", Map.of(
                        "start", "extract_slots",
                        "extract_slots", "check_slots",
                        "check_slots", Map.of("complete", "end", "missing", "collect_info"),
                        "collect_info", "end"
                )
        );
    }

    private Map<String, Object> flightBookingV2() {
        return Map.of(
                "workflow_code", "flight_booking",
                "workflow_version", "2.0.0",
                "entry", "start",
                "nodes", Map.of(
                        "start", Map.of("id", "start", "type", "start"),
                        "extract_slots", Map.of("id", "extract_slots", "type", "llm", "config", Map.of("prompt", "slot_extraction", "model_profile_ref", "structured-extraction-v1")),
                        "check_slots", Map.of("id", "check_slots", "type", "condition", "config", Map.of("required_fields", List.of("departure_city", "arrival_city", "departure_date"))),
                        "collect_info", Map.of("id", "collect_info", "type", "form", "config", bookingForm("请补充航班需求", "需要完整的出发地、目的地和日期。")),
                        "search_flights", Map.of("id", "search_flights", "type", "tool", "config", Map.of("tool_code", "flight_search_api", "url", "http://localhost:19001/api/flights/search", "method", "POST", "retry_policy", "network_timeout", "idempotent", true)),
                        "check_seat_availability", Map.of("id", "check_seat_availability", "type", "subflow", "config", Map.of("subflow_code", "seat_check", "subflow_version", "1.0.0")),
                        "end", Map.of("id", "end", "type", "end", "config", Map.of("output_format", Map.of(
                                "departure_city", "execution.departure_city",
                                "arrival_city", "execution.arrival_city",
                                "departure_date", "execution.departure_date",
                                "passengers", "execution.passengers",
                                "flight_options", "execution.flight_options",
                                "seat_available", "execution.seat_available"
                        )))
                ),
                "transitions", Map.of(
                        "start", "extract_slots",
                        "extract_slots", "check_slots",
                        "check_slots", Map.of("complete", "search_flights", "missing", "collect_info"),
                        "collect_info", "search_flights",
                        "search_flights", "check_seat_availability",
                        "check_seat_availability", "end"
                )
        );
    }

    private Map<String, Object> hotelBookingV1() {
        return Map.of(
                "workflow_code", "hotel_booking",
                "workflow_version", "1.0.0",
                "entry", "start",
                "nodes", Map.of(
                        "start", Map.of("id", "start", "type", "start"),
                        "extract_slots", Map.of("id", "extract_slots", "type", "llm", "config", Map.of("prompt", "hotel_slot_extraction", "model_profile_ref", "structured-extraction-v1")),
                        "collect_info", Map.of("id", "collect_info", "type", "form", "config", Map.of(
                                "title", "请补充酒店需求",
                                "description", "需要目的地和入住日期。",
                                "fields", List.of(
                                        Map.of("name", "arrival_city", "type", "text", "required", true, "label", "目的城市"),
                                        Map.of("name", "departure_date", "type", "date", "required", true, "label", "入住日期"),
                                        Map.of("name", "nights", "type", "number", "required", false, "label", "入住晚数")
                                )
                        )),
                        "search_hotels", Map.of("id", "search_hotels", "type", "tool", "config", Map.of("tool_code", "hotel_search_api", "url", "http://localhost:19001/api/hotels/search", "method", "POST", "retry_policy", "network_timeout", "idempotent", true)),
                        "end", Map.of("id", "end", "type", "end", "config", Map.of("output_format", Map.of(
                                "arrival_city", "execution.arrival_city",
                                "departure_date", "execution.departure_date",
                                "hotel_options", "execution.hotel_options"
                        )))
                ),
                "transitions", Map.of(
                        "start", "extract_slots",
                        "extract_slots", "collect_info",
                        "collect_info", "search_hotels",
                        "search_hotels", "end"
                )
        );
    }

    private Map<String, Object> generalQueryV1() {
        return Map.of(
                "workflow_code", "general_query",
                "workflow_version", "1.0.0",
                "entry", "start",
                "nodes", Map.of(
                        "start", Map.of("id", "start", "type", "start"),
                        "retrieve_policy", Map.of("id", "retrieve_policy", "type", "knowledge", "config", Map.of(
                                "knowledge_base_code", "flight_policy_kb",
                                "kb_version", "1.0.0",
                                "retrieval_mode", "hybrid",
                                "top_k", 3,
                                "query_rewrite", Map.of("enabled", true, "model_profile_ref", "knowledge-query-rewrite-v1"),
                                "answer_generation", Map.of("enabled", true, "model_profile_ref", "knowledge-answer-v1")
                        )),
                        "answer_query", Map.of("id", "answer_query", "type", "llm", "config", Map.of("prompt", "knowledge_answer", "model_profile_ref", "knowledge-answer-v1")),
                        "end", Map.of("id", "end", "type", "end", "config", Map.of("output_format", Map.of(
                                "answer", "execution.answer",
                                "retrieved_docs", "execution.retrieved_docs"
                        )))
                ),
                "transitions", Map.of(
                        "start", "retrieve_policy",
                        "retrieve_policy", "answer_query",
                        "answer_query", "end"
                )
        );
    }

    private Map<String, Object> bookingForm(String title, String description) {
        return Map.of(
                "title", title,
                "description", description,
                "fields", List.of(
                        Map.of("name", "departure_city", "type", "text", "required", true, "label", "出发城市"),
                        Map.of("name", "arrival_city", "type", "text", "required", true, "label", "到达城市"),
                        Map.of("name", "departure_date", "type", "date", "required", true, "label", "出发日期"),
                        Map.of("name", "passengers", "type", "number", "required", false, "label", "乘客数")
                )
        );
    }
}
