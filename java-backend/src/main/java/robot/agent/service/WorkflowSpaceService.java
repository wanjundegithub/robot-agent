package robot.agent.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import robot.agent.common.ApplicationConstants;
import robot.agent.dto.request.UpsertWorkflowSpaceRequest;
import robot.agent.dto.response.WorkflowSpaceResponse;
import robot.agent.model.WorkflowSpace;
import robot.agent.model.WorkflowStatus;
import robot.agent.repository.WorkflowSpaceRepository;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Transactional
public class WorkflowSpaceService {
    public static final String DEFAULT_SPACE_CODE = "default_workflow_space";

    private final WorkflowSpaceRepository repository;
    private final AccessControlService accessControlService;

    public WorkflowSpaceService(WorkflowSpaceRepository repository, AccessControlService accessControlService) {
        this.repository = repository;
        this.accessControlService = accessControlService;
    }

    public List<WorkflowSpaceResponse> listSpaces() {
        ensureDefaultSpaceRecord("system", ApplicationConstants.DEFAULT_WORKSPACE_ID);
        return repository.findByStatusNotOrderByCreatedAtDesc(WorkflowStatus.ARCHIVED)
                .stream()
                .map(WorkflowSpaceResponse::fromEntity)
                .toList();
    }

    public WorkflowSpaceResponse upsertSpace(String userId, UpsertWorkflowSpaceRequest request) {
        if (request == null || isBlank(request.getSpaceCode())) {
            throw new IllegalArgumentException("space_code is required");
        }
        Long workspaceId = request.getWorkspaceId() == null ? ApplicationConstants.DEFAULT_WORKSPACE_ID : request.getWorkspaceId();
        accessControlService.requireWorkflowAdminAction(userId, workspaceId, request.getSpaceCode(), "workflow_space.upsert");
        WorkflowSpace space = repository.findBySpaceCode(request.getSpaceCode()).orElseGet(WorkflowSpace::new);
        space.setWorkspaceId(workspaceId);
        space.setSpaceCode(request.getSpaceCode());
        space.setName(isBlank(request.getName()) ? request.getSpaceCode() : request.getName());
        space.setDescription(request.getDescription());
        space.setStatus(WorkflowStatus.PUBLISHED);
        if (!isBlank(request.getCreatedBy()) && space.getCreatedBy() == null) {
            space.setCreatedBy(request.getCreatedBy());
        } else if (space.getCreatedBy() == null) {
            space.setCreatedBy(userId);
        }
        space.setUpdatedAt(LocalDateTime.now());
        WorkflowSpace saved = repository.save(space);
        return WorkflowSpaceResponse.fromEntity(saved == null ? space : saved);
    }

    public WorkflowSpaceResponse ensureDefaultSpace(String userId, Long workspaceId) {
        return WorkflowSpaceResponse.fromEntity(ensureDefaultSpaceRecord(userId, workspaceId));
    }

    private WorkflowSpace ensureDefaultSpaceRecord(String userId, Long workspaceId) {
        return repository.findBySpaceCode(DEFAULT_SPACE_CODE)
                .orElseGet(() -> {
                    WorkflowSpace space = new WorkflowSpace();
                    space.setWorkspaceId(workspaceId == null ? ApplicationConstants.DEFAULT_WORKSPACE_ID : workspaceId);
                    space.setSpaceCode(DEFAULT_SPACE_CODE);
                    space.setName("默认工作流空间");
                    space.setDescription("系统默认工作流空间");
                    space.setCreatedBy(isBlank(userId) ? "system" : userId);
                    space.setStatus(WorkflowStatus.PUBLISHED);
                    space.setUpdatedAt(LocalDateTime.now());
                    WorkflowSpace saved = repository.save(space);
                    return saved == null ? space : saved;
                });
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
