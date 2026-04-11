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
import robot.agent.repository.UserRoleRepository;
import robot.agent.repository.WorkflowRepository;
import robot.agent.repository.WorkflowVersionRepository;

import java.util.List;
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

    private WorkflowService workflowService;

    @BeforeEach
    void setUp() {
        workflowService = new WorkflowService(
                workflowRepository,
                workflowVersionRepository,
                new ObjectMapper(),
                new AccessControlService(userRoleRepository),
                new AuditService(auditLogRepository, new ObjectMapper())
        );
    }

    @Test
    void routeMessageUsesDynamicThresholdAndFallsBackForShortQuery() {
        Workflow flightWorkflow = publishedWorkflow("flight_booking", "2.0.0");
        Workflow generalWorkflow = publishedWorkflow("general_query", "1.0.0");

        WorkflowVersion flightVersion = workflowVersion("flight_booking", "2.0.0",
                "{\"keywords\":[\"机票\",\"航班\"],\"priority\":120}");
        WorkflowVersion generalVersion = workflowVersion("general_query", "1.0.0",
                "{\"keywords\":[\"政策\",\"规则\"],\"priority\":90}");

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
                "{\"keywords\":[\"酒店\",\"住宿\"],\"priority\":110}");
        WorkflowVersion flightVersion = workflowVersion("flight_booking", "2.0.0",
                "{\"keywords\":[\"机票\",\"航班\"],\"priority\":120}");

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

    private Workflow publishedWorkflow(String workflowCode, String currentVersion) {
        Workflow workflow = new Workflow();
        workflow.setWorkflowCode(workflowCode);
        workflow.setStatus(WorkflowStatus.PUBLISHED);
        workflow.setCurrentVersion(currentVersion);
        workflow.setWorkspaceId(1L);
        return workflow;
    }

    private WorkflowVersion workflowVersion(String workflowCode, String version, String entryRule) {
        WorkflowVersion workflowVersion = new WorkflowVersion();
        workflowVersion.setWorkflowCode(workflowCode);
        workflowVersion.setVersion(version);
        workflowVersion.setStatus(WorkflowVersionStatus.PUBLISHED);
        workflowVersion.setEntryRule(entryRule);
        workflowVersion.setDefinition("{\"nodes\":{}}");
        return workflowVersion;
    }
}
