package robot.agent.service.robot;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import robot.agent.common.ApplicationConstants;
import robot.agent.dto.request.UpdateRobotBindingsRequest;
import robot.agent.dto.request.UpsertRobotConfigRequest;
import robot.agent.dto.response.RobotBindingResponse;
import robot.agent.dto.response.RobotConfigResponse;
import robot.agent.model.RobotBinding;
import robot.agent.model.RobotBindingType;
import robot.agent.model.RobotConfig;
import robot.agent.model.RobotStatus;
import robot.agent.repository.RobotBindingRepository;
import robot.agent.repository.RobotConfigRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@Transactional
public class RobotConfigService {
    private static final List<RobotBindingType> CURRENT_BINDING_TYPES = List.of(
            RobotBindingType.WORKFLOW_SPACE,
            RobotBindingType.KNOWLEDGE_SPACE
    );

    private final RobotConfigRepository configRepository;
    private final RobotBindingRepository bindingRepository;

    public RobotConfigService(RobotConfigRepository configRepository, RobotBindingRepository bindingRepository) {
        this.configRepository = configRepository;
        this.bindingRepository = bindingRepository;
    }

    public List<RobotConfigResponse> listRobots() {
        return configRepository.findByStatusNotOrderByCreatedAtDesc(RobotStatus.ARCHIVED)
                .stream()
                .map(robot -> RobotConfigResponse.fromEntity(robot, findCurrentBindings(robot.getRobotCode())))
                .toList();
    }

    public RobotConfigResponse getRobot(String robotCode) {
        RobotConfig robot = requireRobot(robotCode);
        return RobotConfigResponse.fromEntity(robot, findCurrentBindings(robotCode));
    }

    public RobotConfigResponse upsertRobot(UpsertRobotConfigRequest request) {
        if (request == null || isBlank(request.getRobotCode())) {
            throw new IllegalArgumentException("robot_code is required");
        }
        RobotConfig robot = configRepository.findByRobotCode(request.getRobotCode())
                .orElseGet(RobotConfig::new);
        robot.setWorkspaceId(request.getWorkspaceId() == null ? ApplicationConstants.DEFAULT_WORKSPACE_ID : request.getWorkspaceId());
        robot.setRobotCode(request.getRobotCode());
        robot.setName(isBlank(request.getName()) ? request.getRobotCode() : request.getName());
        robot.setDescription(request.getDescription());
        robot.setAvatar(request.getAvatar());
        robot.setOpeningMessage(request.getOpeningMessage());
        robot.setStatus(request.getStatus() == null ? RobotStatus.DRAFT : request.getStatus());
        robot.setDefaultModelCode(request.getDefaultModelCode());
        robot.setRouteStrategy(isBlank(request.getRouteStrategy()) ? "PARALLEL_AGGREGATE" : request.getRouteStrategy());
        if (!isBlank(request.getCreatedBy()) && robot.getCreatedBy() == null) {
            robot.setCreatedBy(request.getCreatedBy());
        }
        robot.setUpdatedAt(LocalDateTime.now());
        RobotConfig saved = configRepository.save(robot);
        if (saved == null) {
            saved = robot;
        }
        return RobotConfigResponse.fromEntity(saved, findCurrentBindings(saved.getRobotCode()));
    }

    public RobotConfigResponse publishRobot(String robotCode) {
        RobotConfig robot = requireRobot(robotCode);
        List<RobotBinding> bindings = findCurrentBindings(robotCode);
        validatePublishBindings(bindings);
        robot.setStatus(RobotStatus.PUBLISHED);
        robot.setUpdatedAt(LocalDateTime.now());
        RobotConfig saved = configRepository.save(robot);
        if (saved == null) {
            saved = robot;
        }
        return RobotConfigResponse.fromEntity(saved, bindings);
    }

    public List<RobotBindingResponse> getBindings(String robotCode) {
        requireRobot(robotCode);
        return findCurrentBindings(robotCode)
                .stream()
                .map(RobotBindingResponse::fromEntity)
                .toList();
    }

    public List<RobotBindingResponse> replaceBindings(String robotCode, UpdateRobotBindingsRequest request) {
        RobotConfig robot = requireRobot(robotCode);
        List<RobotBinding> existing = findCurrentBindings(robotCode);
        int nextVersion = existing.stream()
                .map(RobotBinding::getBindingVersion)
                .mapToInt(value -> value == null ? 1 : value)
                .max()
                .orElse(0) + 1;
        for (RobotBinding binding : existing) {
            binding.setEnabled(false);
            binding.setUpdatedAt(LocalDateTime.now());
            bindingRepository.save(binding);
        }

        List<RobotBinding> saved = new ArrayList<>();
        Long workspaceId = request == null || request.getWorkspaceId() == null ? robot.getWorkspaceId() : request.getWorkspaceId();
        if (request != null) {
            appendBindings(saved, workspaceId, robotCode, RobotBindingType.WORKFLOW_SPACE, request.getWorkflowSpaceCodes(), nextVersion);
            appendBindings(saved, workspaceId, robotCode, RobotBindingType.KNOWLEDGE_SPACE, request.getKbCodes(), nextVersion);
        }
        return saved.stream().map(RobotBindingResponse::fromEntity).toList();
    }

    public RobotRuntimeContext resolveRuntimeContext(String robotCode) {
        if (isBlank(robotCode)) {
            throw new IllegalArgumentException("robot_code is required");
        }
        RobotConfig robot = requireRobot(robotCode);
        if (robot.getStatus() != RobotStatus.PUBLISHED) {
            throw new IllegalStateException("Robot is not published: " + robotCode);
        }
        List<RobotBinding> bindings = findCurrentBindings(robotCode);
        int bindingVersion = bindings.stream()
                .map(RobotBinding::getBindingVersion)
                .mapToInt(value -> value == null ? 1 : value)
                .max()
                .orElse(0);
        List<String> workflowSpaceCodes = bindings.stream()
                .filter(binding -> binding.getBindingType() == RobotBindingType.WORKFLOW_SPACE)
                .map(RobotBinding::getTargetCode)
                .filter(code -> !isBlank(code))
                .distinct()
                .toList();
        List<String> kbCodes = bindings.stream()
                .filter(binding -> binding.getBindingType() == RobotBindingType.KNOWLEDGE_SPACE)
                .map(RobotBinding::getTargetCode)
                .filter(code -> !isBlank(code))
                .distinct()
                .toList();
        return new RobotRuntimeContext(
                robot.getRobotCode(),
                robot.getWorkspaceId(),
                bindingVersion,
                workflowSpaceCodes,
                kbCodes,
                robot.getRouteStrategy()
        );
    }

    private RobotConfig requireRobot(String robotCode) {
        return configRepository.findByRobotCode(robotCode)
                .orElseThrow(() -> new IllegalArgumentException("Robot not found: " + robotCode));
    }

    private List<RobotBinding> findCurrentBindings(String robotCode) {
        return bindingRepository.findByRobotCodeAndEnabledTrueAndBindingTypeInOrderByCreatedAtAsc(
                robotCode,
                CURRENT_BINDING_TYPES
        );
    }

    private void appendBindings(
            List<RobotBinding> saved,
            Long workspaceId,
            String robotCode,
            RobotBindingType bindingType,
            List<String> targetCodes,
            int bindingVersion
    ) {
        if (targetCodes == null) {
            return;
        }
        for (String targetCode : targetCodes) {
            if (isBlank(targetCode)) {
                continue;
            }
            RobotBinding binding = new RobotBinding();
            binding.setWorkspaceId(workspaceId);
            binding.setRobotCode(robotCode);
            binding.setBindingType(bindingType);
            binding.setTargetCode(targetCode);
            binding.setBindingVersion(bindingVersion);
            binding.setEnabled(true);
            binding.setUpdatedAt(LocalDateTime.now());
            RobotBinding persisted = bindingRepository.save(binding);
            saved.add(persisted == null ? binding : persisted);
        }
    }

    private void validatePublishBindings(List<RobotBinding> bindings) {
        List<RobotBinding> safeBindings = bindings == null ? List.of() : bindings;
        boolean hasWorkflowSpace = safeBindings.stream()
                .anyMatch(binding -> binding.getBindingType() == RobotBindingType.WORKFLOW_SPACE && !isBlank(binding.getTargetCode()));
        if (!hasWorkflowSpace) {
            throw new IllegalStateException("Robot publish requires at least one workflow space binding");
        }
        boolean hasKnowledgeSpace = safeBindings.stream()
                .anyMatch(binding -> binding.getBindingType() == RobotBindingType.KNOWLEDGE_SPACE && !isBlank(binding.getTargetCode()));
        if (!hasKnowledgeSpace) {
            throw new IllegalStateException("Robot publish requires at least one knowledge space binding");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
