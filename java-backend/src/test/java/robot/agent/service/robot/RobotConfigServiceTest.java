package robot.agent.service.robot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import robot.agent.model.RobotBinding;
import robot.agent.model.RobotBindingType;
import robot.agent.model.RobotConfig;
import robot.agent.model.RobotStatus;
import robot.agent.repository.RobotBindingRepository;
import robot.agent.repository.RobotConfigRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RobotConfigServiceTest {

    @Mock
    private RobotConfigRepository configRepository;

    @Mock
    private RobotBindingRepository bindingRepository;

    private RobotConfigService service;

    @BeforeEach
    void setUp() {
        service = new RobotConfigService(configRepository, bindingRepository);
    }

    @Test
    void runtimeContextRequiresPublishedRobot() {
        RobotConfig disabled = robot("robot_after_sale", RobotStatus.DISABLED);
        when(configRepository.findByRobotCode("robot_after_sale")).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> service.resolveRuntimeContext("robot_after_sale"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Robot is not published");
    }

    @Test
    void publishRobotMarksDraftAsPublished() {
        RobotConfig draft = robot("robot_after_sale", RobotStatus.DRAFT);
        when(configRepository.findByRobotCode("robot_after_sale")).thenReturn(Optional.of(draft));
        when(configRepository.save(draft)).thenReturn(draft);
        when(bindingRepository.findByRobotCodeAndEnabledTrueAndBindingTypeInOrderByCreatedAtAsc(
                "robot_after_sale",
                currentBindingTypes()
        )).thenReturn(List.of(
                binding(RobotBindingType.WORKFLOW_SPACE, "after_sale_space", 1),
                binding(RobotBindingType.KNOWLEDGE_SPACE, "kb_warranty_policy", 1)
        ));

        assertThat(service.publishRobot("robot_after_sale").getStatus()).isEqualTo(RobotStatus.PUBLISHED);
    }

    @Test
    void publishRobotRequiresWorkflowSpaceAndKnowledgeSpaceBindings() {
        RobotConfig draft = robot("robot_after_sale", RobotStatus.DRAFT);
        when(configRepository.findByRobotCode("robot_after_sale")).thenReturn(Optional.of(draft));
        when(bindingRepository.findByRobotCodeAndEnabledTrueAndBindingTypeInOrderByCreatedAtAsc(
                "robot_after_sale",
                currentBindingTypes()
        ))
                .thenReturn(List.of(binding(RobotBindingType.WORKFLOW_SPACE, "after_sale_space", 1)));

        assertThatThrownBy(() -> service.publishRobot("robot_after_sale"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("knowledge space binding");
    }

    @Test
    void runtimeContextReturnsWorkflowSpaceAndKnowledgeBindings() {
        RobotConfig published = robot("robot_after_sale", RobotStatus.PUBLISHED);
        when(configRepository.findByRobotCode("robot_after_sale")).thenReturn(Optional.of(published));
        when(bindingRepository.findByRobotCodeAndEnabledTrueAndBindingTypeInOrderByCreatedAtAsc(
                "robot_after_sale",
                currentBindingTypes()
        ))
                .thenReturn(List.of(
                        binding(RobotBindingType.WORKFLOW_SPACE, "after_sale_space", 3),
                        binding(RobotBindingType.KNOWLEDGE_SPACE, "kb_warranty_policy", 3)
                ));

        RobotRuntimeContext context = service.resolveRuntimeContext("robot_after_sale");

        assertThat(context.robotCode()).isEqualTo("robot_after_sale");
        assertThat(context.workflowSpaceCodes()).containsExactly("after_sale_space");
        assertThat(context.kbCodes()).containsExactly("kb_warranty_policy");
        assertThat(context.bindingVersion()).isEqualTo(3);
    }

    @Test
    void listRobotsOnlyReadsCurrentBindingTypes() {
        RobotConfig published = robot("robot_after_sale", RobotStatus.PUBLISHED);
        when(configRepository.findByStatusNotOrderByCreatedAtDesc(RobotStatus.ARCHIVED))
                .thenReturn(List.of(published));
        when(bindingRepository.findByRobotCodeAndEnabledTrueAndBindingTypeInOrderByCreatedAtAsc(
                "robot_after_sale",
                currentBindingTypes()
        )).thenReturn(List.of(
                binding(RobotBindingType.WORKFLOW_SPACE, "after_sale_space", 2),
                binding(RobotBindingType.KNOWLEDGE_SPACE, "kb_warranty_policy", 2)
        ));

        assertThat(service.listRobots())
                .singleElement()
                .satisfies(response -> {
                    assertThat(response.getRobotCode()).isEqualTo("robot_after_sale");
                    assertThat(response.getWorkflowBindingCount()).isEqualTo(1);
                    assertThat(response.getKnowledgeBindingCount()).isEqualTo(1);
                });
    }

    private RobotConfig robot(String robotCode, RobotStatus status) {
        RobotConfig robot = new RobotConfig();
        robot.setWorkspaceId(1L);
        robot.setRobotCode(robotCode);
        robot.setName(robotCode);
        robot.setStatus(status);
        return robot;
    }

    private List<RobotBindingType> currentBindingTypes() {
        return List.of(RobotBindingType.WORKFLOW_SPACE, RobotBindingType.KNOWLEDGE_SPACE);
    }

    private RobotBinding binding(RobotBindingType type, String targetCode, int bindingVersion) {
        RobotBinding binding = new RobotBinding();
        binding.setWorkspaceId(1L);
        binding.setRobotCode("robot_after_sale");
        binding.setBindingType(type);
        binding.setTargetCode(targetCode);
        binding.setBindingVersion(bindingVersion);
        binding.setEnabled(true);
        return binding;
    }
}
