package robot.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import robot.agent.common.ApplicationConstants;
import robot.agent.model.KnowledgeBase;
import robot.agent.model.KnowledgeBaseStatus;
import robot.agent.model.KnowledgeVersion;
import robot.agent.model.KnowledgeVersionStatus;
import robot.agent.model.Role;
import robot.agent.model.UserRole;
import robot.agent.model.UserRoleId;
import robot.agent.model.Workflow;
import robot.agent.model.WorkflowStatus;
import robot.agent.model.WorkflowVersion;
import robot.agent.model.WorkflowVersionStatus;
import robot.agent.repository.KnowledgeBaseRepository;
import robot.agent.repository.KnowledgeVersionRepository;
import robot.agent.repository.RoleRepository;
import robot.agent.repository.UserRoleRepository;
import robot.agent.repository.WorkflowRepository;
import robot.agent.repository.WorkflowVersionRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Component
public class DemoWorkflowDataInitializer implements ApplicationRunner {

    private final WorkflowRepository workflowRepository;
    private final WorkflowVersionRepository workflowVersionRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeVersionRepository knowledgeVersionRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final ObjectMapper objectMapper;

    public DemoWorkflowDataInitializer(
            WorkflowRepository workflowRepository,
            WorkflowVersionRepository workflowVersionRepository,
            KnowledgeBaseRepository knowledgeBaseRepository,
            KnowledgeVersionRepository knowledgeVersionRepository,
            RoleRepository roleRepository,
            UserRoleRepository userRoleRepository,
            ObjectMapper objectMapper
    ) {
        this.workflowRepository = workflowRepository;
        this.workflowVersionRepository = workflowVersionRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.knowledgeVersionRepository = knowledgeVersionRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        seedRoles();
        seedKnowledgeBase();
    }

    private void seedRoles() {
        createRoleIfMissing("workflow_admin", "Workflow Admin", "Can manage workflow definitions and versions.");
        createRoleIfMissing("knowledge_admin", "Knowledge Admin", "Can manage knowledge bases and versions.");
        createRoleIfMissing("viewer", "Viewer", "Read-only access to workflow resources.");

        assignRoleIfMissing("demo-admin", ApplicationConstants.DEFAULT_WORKSPACE_ID, "workflow_admin");
        assignRoleIfMissing("demo-admin", ApplicationConstants.DEFAULT_WORKSPACE_ID, "knowledge_admin");
        assignRoleIfMissing("demo-admin", ApplicationConstants.DEFAULT_WORKSPACE_ID, "viewer");
        assignRoleIfMissing("demo-user", ApplicationConstants.DEFAULT_WORKSPACE_ID, "viewer");
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
                    base.setWorkspaceId(ApplicationConstants.DEFAULT_WORKSPACE_ID);
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

    private void seedWorkflows() {
        seedWorkflowDefinition("flight_booking", "Flight Booking", "Flight booking workflow.", "2.0.0");
        seedWorkflowDefinition("hotel_booking", "Hotel Booking", "Hotel booking workflow.", "1.0.0");
        seedWorkflowDefinition("general_query", "General Query", "Knowledge-assisted query workflow.", "1.0.0");

        seedWorkflowVersion("flight_booking", "1.0.0", flightBookingV1(), entryRule(List.of("flight", "ticket", "booking"), 100));
        seedWorkflowVersion("flight_booking", "2.0.0", flightBookingV2(), entryRule(List.of("flight", "ticket", "booking"), 120));
        seedWorkflowVersion("hotel_booking", "1.0.0", hotelBookingV1(), entryRule(List.of("hotel", "room"), 110));
        seedWorkflowVersion("general_query", "1.0.0", generalQueryV1(), entryRule(List.of("policy", "refund"), 90));
    }

    private void seedWorkflowDefinition(String workflowCode, String name, String description, String currentVersion) {
        Workflow workflow = workflowRepository.findByWorkflowCode(workflowCode)
                .orElseGet(() -> {
                    Workflow created = new Workflow();
                    created.setWorkspaceId(ApplicationConstants.DEFAULT_WORKSPACE_ID);
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

    private void seedWorkflowVersion(String workflowCode, String version, Map<String, Object> definition, Map<String, Object> entryRule) {
        if (workflowVersionRepository.findByWorkflowCodeAndVersion(workflowCode, version).isPresent()) {
            return;
        }
        WorkflowVersion workflowVersion = new WorkflowVersion();
        workflowVersion.setWorkflowCode(workflowCode);
        workflowVersion.setVersion(version);
        workflowVersion.setStatus(WorkflowVersionStatus.PUBLISHED);
        workflowVersion.setDefinition(writeJson(definition));
        workflowVersion.setEntryRule(writeJson(entryRule));
        workflowVersion.setEditorMeta(writeJson(Map.of(
                "layout_engine", "reactflow",
                "viewport", Map.of("x", 0, "y", 0, "zoom", 0.92),
                "readonly", false,
                "last_saved_by", "system"
        )));
        workflowVersion.setConfig(writeJson(Map.of(
                "routing_model_code", "intent-router-v1",
                "llm_defaults", Map.of("model_code", "general-chat-v1", "provider_code", "openai-compatible-prod")
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
                        "extract_slots", Map.of("id", "extract_slots", "type", "llm", "config", Map.of("prompt", "slot_extraction", "model_code", "structured-extraction-v1")),
                        "check_slots", Map.of("id", "check_slots", "type", "condition", "config", Map.of("required_fields", List.of("departure_city", "arrival_city", "departure_date"))),
                        "collect_info", Map.of("id", "collect_info", "type", "form", "config", bookingForm("Provide trip details", "Please provide missing booking details.")),
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
                        "extract_slots", Map.of("id", "extract_slots", "type", "llm", "config", Map.of("prompt", "slot_extraction", "model_code", "structured-extraction-v1")),
                        "check_slots", Map.of("id", "check_slots", "type", "condition", "config", Map.of("required_fields", List.of("departure_city", "arrival_city", "departure_date"))),
                        "collect_info", Map.of("id", "collect_info", "type", "form", "config", bookingForm("Provide flight details", "Origin, destination, and date are required.")),
                        "search_flights", Map.of("id", "search_flights", "type", "tool", "config", Map.of("tool_code", "flight_search_api", "url", "http://localhost:19001/api/flights/search", "method", "POST")),
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
                        "extract_slots", Map.of("id", "extract_slots", "type", "llm", "config", Map.of("prompt", "hotel_slot_extraction", "model_code", "structured-extraction-v1")),
                        "collect_info", Map.of("id", "collect_info", "type", "form", "config", Map.of(
                                "title", "Provide hotel details",
                                "description", "Destination and check-in date are required.",
                                "fields", List.of(
                                        Map.of("name", "arrival_city", "type", "text", "required", true, "label", "Destination city"),
                                        Map.of("name", "departure_date", "type", "date", "required", true, "label", "Check-in date"),
                                        Map.of("name", "nights", "type", "number", "required", false, "label", "Nights")
                                )
                        )),
                        "search_hotels", Map.of("id", "search_hotels", "type", "tool", "config", Map.of("tool_code", "hotel_search_api", "url", "http://localhost:19001/api/hotels/search", "method", "POST")),
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
                                "query_rewrite", Map.of("enabled", true, "model_code", "knowledge-query-rewrite-v1"),
                                "answer_generation", Map.of("enabled", true, "model_code", "knowledge-answer-v1")
                        )),
                        "answer_query", Map.of("id", "answer_query", "type", "llm", "config", Map.of("prompt", "knowledge_answer", "model_code", "knowledge-answer-v1")),
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
                        Map.of("name", "departure_city", "type", "text", "required", true, "label", "Origin city"),
                        Map.of("name", "arrival_city", "type", "text", "required", true, "label", "Destination city"),
                        Map.of("name", "departure_date", "type", "date", "required", true, "label", "Departure date"),
                        Map.of("name", "passengers", "type", "number", "required", false, "label", "Passengers")
                )
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize seed payload", exception);
        }
    }
}
