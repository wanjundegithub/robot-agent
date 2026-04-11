package robot.agent.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import robot.agent.repository.UserRoleRepository;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ConfirmationServiceTest {

    @Mock
    private UserRoleRepository userRoleRepository;

    @Test
    void evaluateCreatesAndConsumesHighRiskConfirmationTicket() {
        ConfirmationService service = new ConfirmationService(new AccessControlService(userRoleRepository));

        ConfirmationService.ConfirmationEvaluation required = service.evaluate(
                "sess_1",
                "demo-user",
                "请帮我取消订单",
                null,
                null,
                false
        );

        ConfirmationService.ConfirmationEvaluation approved = service.evaluate(
                "sess_1",
                "demo-user",
                "请帮我取消订单",
                required.toolCode(),
                required.confirmationId(),
                false
        );

        assertThat(required.requiresConfirmation()).isTrue();
        assertThat(required.toolCode()).isEqualTo("cancel_order");
        assertThat(required.confirmationId()).isNotBlank();
        assertThat(approved.requiresConfirmation()).isFalse();
        assertThat(approved.toolCode()).isEqualTo("cancel_order");
    }

    @Test
    void evaluateCancelsExistingTicket() {
        ConfirmationService service = new ConfirmationService(new AccessControlService(userRoleRepository));
        ConfirmationService.ConfirmationEvaluation required = service.evaluate(
                "sess_2",
                "demo-user",
                "请帮我更新权限",
                null,
                null,
                false
        );

        ConfirmationService.ConfirmationEvaluation cancelled = service.evaluate(
                "sess_2",
                "demo-user",
                "请帮我更新权限",
                required.toolCode(),
                required.confirmationId(),
                true
        );

        assertThat(cancelled.cancelled()).isTrue();
        assertThat(cancelled.toolCode()).isEqualTo("update_permission");
    }
}
