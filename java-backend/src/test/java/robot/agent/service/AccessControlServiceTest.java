package robot.agent.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import robot.agent.model.Role;
import robot.agent.model.UserRole;
import robot.agent.model.UserRoleId;
import robot.agent.repository.UserRoleRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessControlServiceTest {

    @Mock
    private UserRoleRepository userRoleRepository;

    @Test
    void executionAccessAllowsViewerOnTravelWorkflow() {
        when(userRoleRepository.findByIdUserIdAndIdWorkspaceId("demo-user", 1L))
                .thenReturn(List.of(userRole("demo-user", 1L, "viewer")));

        AccessControlService service = new AccessControlService(userRoleRepository);
        AccessControlService.AuthorizationDecision decision = service.evaluateExecutionAccess(
                "demo-user",
                1L,
                "flight_booking",
                null,
                "demo-user"
        );

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reason()).isEqualTo("policy_allow_execution");
    }

    @Test
    void executionAccessDeniesAnonymousHighRiskEvenWithViewerRole() {
        when(userRoleRepository.findByIdUserIdAndIdWorkspaceId("anonymous", 1L))
                .thenReturn(List.of(userRole("anonymous", 1L, "viewer")));

        AccessControlService service = new AccessControlService(userRoleRepository);
        AccessControlService.AuthorizationDecision decision = service.evaluateExecutionAccess(
                "anonymous",
                1L,
                "general_query",
                "cancel_order",
                "anonymous"
        );

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("high_risk_requires_trusted_user");
    }

    private UserRole userRole(String userId, Long workspaceId, String roleCode) {
        Role role = new Role();
        role.setId(1);
        role.setCode(roleCode);

        UserRoleId userRoleId = new UserRoleId();
        userRoleId.setUserId(userId);
        userRoleId.setWorkspaceId(workspaceId);
        userRoleId.setRoleId(role.getId());

        UserRole userRole = new UserRole();
        userRole.setId(userRoleId);
        userRole.setRole(role);
        return userRole;
    }
}
