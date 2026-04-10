package robot.agent.service;

import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import robot.agent.model.UserRole;
import robot.agent.repository.UserRoleRepository;

import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AccessControlService {

    private final UserRoleRepository userRoleRepository;

    public AccessControlService(UserRoleRepository userRoleRepository) {
        this.userRoleRepository = userRoleRepository;
    }

    public void requireAnyRole(String userId, Long workspaceId, Set<String> allowedRoles) {
        String effectiveUserId = userId == null || userId.isBlank() ? "anonymous" : userId;
        Long effectiveWorkspaceId = workspaceId == null ? 1L : workspaceId;
        Set<String> actualRoles = userRoleRepository.findByIdUserIdAndIdWorkspaceId(effectiveUserId, effectiveWorkspaceId)
                .stream()
                .map(UserRole::getRole)
                .filter(role -> role != null && role.getCode() != null)
                .map(role -> role.getCode().toLowerCase())
                .collect(Collectors.toSet());

        boolean allowed = allowedRoles.stream()
                .map(String::toLowerCase)
                .anyMatch(actualRoles::contains);
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Permission denied for user: " + effectiveUserId);
        }
    }
}
