package robot.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import robot.agent.model.Execution;
import robot.agent.model.ExecutionStatus;
import robot.agent.model.Workflow;
import robot.agent.model.WorkflowStatus;
import robot.agent.model.WorkflowVersion;
import robot.agent.model.WorkflowVersionStatus;
import robot.agent.repository.AuditLogRepository;
import robot.agent.repository.LlmModelProfileRepository;
import robot.agent.repository.LlmProviderConfigRepository;
import robot.agent.repository.UserRoleRepository;
import robot.agent.repository.WorkflowRepository;
import robot.agent.repository.WorkflowVersionRepository;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceTest {

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private WorkflowVersionRepository workflowVersionRepository;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private LlmProviderConfigRepository llmProviderConfigRepository;

    @Mock
    private LlmModelProfileRepository llmModelProfileRepository;

    private WorkflowService workflowService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        AccessControlService accessControlService = new AccessControlService(userRoleRepository);
        AuditService auditService = new AuditService(auditLogRepository, objectMapper);
        workflowService = new WorkflowService(
                workflowRepository,
                workflowVersionRepository,
                objectMapper,
                accessControlService,
                auditService,
                new StubPythonClient(),
                new ModelConfigService(
                        llmProviderConfigRepository,
                        llmModelProfileRepository,
                        objectMapper,
                        accessControlService,
                        auditService
                )
        );
    }

    @Test
    void routeMessageUsesDynamicThresholdAndFallsBackForShortQuery() {
        Workflow flightWorkflow = publishedWorkflow("flight_booking", "2.0.0");
        Workflow generalWorkflow = publishedWorkflow("general_query", "1.0.0");

        WorkflowVersion flightVersion = workflowVersion("flight_booking", "2.0.0",
                "{\"keywords\":[\"机票\",\"航班\"],\"priority\":120}",
                "{\"intent_profile_ref\":\"intent-router-v1\"}");
        WorkflowVersion generalVersion = workflowVersion("general_query", "1.0.0",
                "{\"keywords\":[\"政策\",\"规则\"],\"priority\":90}",
                "{\"intent_profile_ref\":\"intent-router-v1\"}");

        when(workflowRepository.findByStatusOrderByCreatedAtDesc(WorkflowStatus.PUBLISHED))
                .thenReturn(List.of(flightWorkflow, generalWorkflow));
        when(workflowVersionRepository.findByWorkflowCodeAndVersion("flight_booking", "2.0.0"))
                .thenReturn(Optional.of(flightVersion));
        when(workflowVersionRepository.findByWorkflowCodeAndVersion("general_query", "1.0.0"))
                .thenReturn(Optional.of(generalVersion));

        RoutingDecision decision = workflowService.routeMessage("hi", null);

        assertThat(decision.decision()).isEqualTo("fallback");
        assertThat(decision.workflowCode()).isEqualTo("general_query");
        assertThat(decision.thresholdSource()).isEqualTo("dynamic:short_query");
        assertThat(decision.threshold()).isGreaterThanOrEqualTo(0.55d);
    }

    @Test
    void routeMessageRequiresSwitchWhenActiveExecutionConflictsAndThresholdAccepted() {
        Workflow hotelWorkflow = publishedWorkflow("hotel_booking", "1.0.0");
        Workflow flightWorkflow = publishedWorkflow("flight_booking", "2.0.0");

        WorkflowVersion hotelVersion = workflowVersion("hotel_booking", "1.0.0",
                "{\"keywords\":[\"酒店\",\"住宿\"],\"priority\":110}",
                "{\"intent_profile_ref\":\"intent-router-v1\"}");
        WorkflowVersion flightVersion = workflowVersion("flight_booking", "2.0.0",
                "{\"keywords\":[\"机票\",\"航班\"],\"priority\":120}",
                "{\"intent_profile_ref\":\"intent-router-v1\"}");

        when(workflowRepository.findByStatusOrderByCreatedAtDesc(WorkflowStatus.PUBLISHED))
                .thenReturn(List.of(hotelWorkflow, flightWorkflow));
        when(workflowVersionRepository.findByWorkflowCodeAndVersion("hotel_booking", "1.0.0"))
                .thenReturn(Optional.of(hotelVersion));
        when(workflowVersionRepository.findByWorkflowCodeAndVersion("flight_booking", "2.0.0"))
                .thenReturn(Optional.of(flightVersion));

        Execution activeExecution = new Execution();
        activeExecution.setWorkflowCode("hotel_booking");
        activeExecution.setStatus(ExecutionStatus.RUNNING);

        RoutingDecision decision = workflowService.routeMessage("我要订一张去上海的机票", activeExecution);

        assertThat(decision.decision()).isEqualTo("switch_required");
        assertThat(decision.workflowCode()).isEqualTo("flight_booking");
        assertThat(decision.confidence()).isGreaterThanOrEqualTo(decision.threshold());
    }

    @Test
    void validateWorkflowDefinitionRequiresPromptAndToolForm() {
        List<Map<String, Object>> issues = workflowService.validateWorkflowDefinition(
                """
                {"entry":"start","nodes":{"start":{"id":"start","type":"start","config":{}},"coordinate_1":{"id":"coordinate_1","type":"coordinate","config":{}},"tool_1":{"id":"tool_1","type":"tool","config":{"invoke_type":"api"}},"end":{"id":"end","type":"end","config":{}}},"transitions":{"start":"coordinate_1","coordinate_1":"tool_1","tool_1":"end","end":null}}
                """,
                "{}"
        );

        assertThat(issues).isNotEmpty();
        assertThat(issues).anyMatch(issue -> "config.prompt".equals(issue.get("field")));
        assertThat(issues).anyMatch(issue -> "config.url".equals(issue.get("field")));
        assertThat(issues).anyMatch(issue -> "config.method".equals(issue.get("field")));
        assertThat(issues).noneMatch(issue -> "config.initial_variables".equals(issue.get("field")));
        assertThat(issues).noneMatch(issue -> "config.output_format".equals(issue.get("field")));
    }

    private Workflow publishedWorkflow(String workflowCode, String currentVersion) {
        Workflow workflow = new Workflow();
        workflow.setWorkflowCode(workflowCode);
        workflow.setStatus(WorkflowStatus.PUBLISHED);
        workflow.setCurrentVersion(currentVersion);
        workflow.setWorkspaceId(1L);
        return workflow;
    }

    private WorkflowVersion workflowVersion(String workflowCode, String version, String entryRule, String config) {
        WorkflowVersion workflowVersion = new WorkflowVersion();
        workflowVersion.setWorkflowCode(workflowCode);
        workflowVersion.setVersion(version);
        workflowVersion.setStatus(WorkflowVersionStatus.PUBLISHED);
        workflowVersion.setEntryRule(entryRule);
        workflowVersion.setDefinition("{\"nodes\":{},\"config\":{\"intent_profile_ref\":\"intent-router-v1\"}}");
        workflowVersion.setConfig(config);
        return workflowVersion;
    }

    private static class StubPythonClient extends PythonClient {
        StubPythonClient() {
            super("http://localhost:8000");
        }

        @Override
        public Mono<Map<String, Object>> classifyIntent(Map<String, Object> request) {
            String message = String.valueOf(request.getOrDefault("message", ""));
            if (message.length() <= 4) {
                return Mono.just(Map.of(
                        "intent_code", "general_query",
                        "workflow_code", "general_query",
                        "confidence", 0.40d,
                        "reason", "short_query_low_confidence"
                ));
            }
            if (message.contains("酒店")) {
                return Mono.just(Map.of(
                        "intent_code", "book_hotel",
                        "workflow_code", "hotel_booking",
                        "confidence", 0.90d,
                        "reason", "profile_classifier"
                ));
            }
            return Mono.just(Map.of(
                    "intent_code", "book_flight",
                    "workflow_code", "flight_booking",
                    "confidence", 0.93d,
                    "reason", "profile_classifier"
            ));
        }
    }
}
