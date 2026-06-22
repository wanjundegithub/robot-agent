package robot.agent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import robot.agent.dto.response.WorkflowSpaceResponse;
import robot.agent.model.WorkflowSpace;
import robot.agent.model.WorkflowStatus;
import robot.agent.repository.WorkflowSpaceRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowSpaceServiceTest {

    @Mock
    private WorkflowSpaceRepository repository;

    @Mock
    private AccessControlService accessControlService;

    private WorkflowSpaceService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowSpaceService(repository, accessControlService);
    }

    @Test
    void listSpacesCreatesDefaultSpaceWithoutAdminCheck() {
        when(repository.findBySpaceCode(WorkflowSpaceService.DEFAULT_SPACE_CODE)).thenReturn(Optional.empty());
        when(repository.save(any(WorkflowSpace.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findByStatusNotOrderByCreatedAtDesc(WorkflowStatus.ARCHIVED))
                .thenAnswer(invocation -> List.of(defaultSpace()));

        List<WorkflowSpaceResponse> spaces = service.listSpaces();

        assertThat(spaces).hasSize(1);
        assertThat(spaces.get(0).getSpaceCode()).isEqualTo(WorkflowSpaceService.DEFAULT_SPACE_CODE);
        verify(accessControlService, never()).requireWorkflowAdminAction(anyString(), anyLong(), anyString(), anyString());
    }

    private WorkflowSpace defaultSpace() {
        WorkflowSpace space = new WorkflowSpace();
        space.setWorkspaceId(1L);
        space.setSpaceCode(WorkflowSpaceService.DEFAULT_SPACE_CODE);
        space.setName("默认工作流空间");
        space.setStatus(WorkflowStatus.PUBLISHED);
        return space;
    }
}
