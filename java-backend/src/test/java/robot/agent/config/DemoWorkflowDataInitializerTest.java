package robot.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import robot.agent.model.KnowledgeBase;
import robot.agent.model.KnowledgeVersion;
import robot.agent.model.Role;
import robot.agent.model.Workflow;
import robot.agent.model.WorkflowVersion;
import robot.agent.repository.KnowledgeBaseRepository;
import robot.agent.repository.KnowledgeVersionRepository;
import robot.agent.repository.LlmModelRecordRepository;
import robot.agent.repository.LlmProviderConfigRepository;
import robot.agent.repository.RoleRepository;
import robot.agent.repository.UserRoleRepository;
import robot.agent.repository.WorkflowRepository;
import robot.agent.repository.WorkflowVersionRepository;

import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DemoWorkflowDataInitializerTest {

    @Test
    void runDoesNotSeedOrMutateModelConfigurations() {
        WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
        WorkflowVersionRepository workflowVersionRepository = mock(WorkflowVersionRepository.class);
        KnowledgeBaseRepository knowledgeBaseRepository = mock(KnowledgeBaseRepository.class);
        KnowledgeVersionRepository knowledgeVersionRepository = mock(KnowledgeVersionRepository.class);
        RoleRepository roleRepository = mock(RoleRepository.class);
        UserRoleRepository userRoleRepository = mock(UserRoleRepository.class);

        Role role = new Role();
        role.setId(1);
        when(roleRepository.findByCode(anyString())).thenReturn(Optional.of(role));
        when(userRoleRepository.existsById(any())).thenReturn(true);
        when(knowledgeBaseRepository.findByKbCode(anyString())).thenReturn(Optional.of(new KnowledgeBase()));
        when(knowledgeVersionRepository.findByKbCodeAndVersion(anyString(), anyString()))
                .thenReturn(Optional.of(new KnowledgeVersion()));
        when(workflowRepository.findByWorkflowCode(anyString())).thenReturn(Optional.of(new Workflow()));
        when(workflowVersionRepository.findByWorkflowCodeAndVersion(anyString(), anyString()))
                .thenReturn(Optional.of(new WorkflowVersion()));

        DemoWorkflowDataInitializer initializer = new DemoWorkflowDataInitializer(
                workflowRepository,
                workflowVersionRepository,
                knowledgeBaseRepository,
                knowledgeVersionRepository,
                roleRepository,
                userRoleRepository,
                new ObjectMapper()
        );

        assertThatCode(() -> initializer.run(null)).doesNotThrowAnyException();

        assertThat(Arrays.stream(DemoWorkflowDataInitializer.class.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                .toList())
                .doesNotContain(LlmProviderConfigRepository.class, LlmModelRecordRepository.class);
    }
}
