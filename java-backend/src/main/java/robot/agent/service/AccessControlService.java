package robot.agent.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import robot.agent.model.UserRole;
import robot.agent.repository.UserRoleRepository;

import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AccessControlService {

    private static final Set<String> HIGH_RISK_TOOLS = Set.of(
            "delete_account",
            "transfer_money",
            "update_permission",
            "cancel_order"
    );

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

    public AuthorizationDecision evaluateExecutionAccess(
            String userId,
            Long workspaceId,
            String workflowCode,
            String requestedToolCode,
            String sessionOwnerId
    ) {
        String effectiveUserId = normalizeUserId(userId);
        Long effectiveWorkspaceId = workspaceId == null ? 1L : workspaceId;
        Set<String> actualRoles = loadRoles(effectiveUserId, effectiveWorkspaceId);
        Map<String, Object> attributes = resolveUserAttributes(
                effectiveUserId,
                actualRoles,
                workflowCode,
                requestedToolCode,
                sessionOwnerId
        );

        boolean hasExecutionRole = actualRoles.contains("viewer") || actualRoles.contains("workflow_admin");
        if (!hasExecutionRole) {
            return AuthorizationDecision.deny("rbac_deny", "missing_execution_role", actualRoles, attributes);
        }

        if ((workflowCode == null || workflowCode.isBlank()) && !isHighRiskTool(requestedToolCode)) {
            return AuthorizationDecision.allow("policy_allow_default", actualRoles, attributes);
        }

        if (isHighRiskTool(requestedToolCode)
                && sessionOwnerId != null
                && !sessionOwnerId.isBlank()
                && !"anonymous".equalsIgnoreCase(sessionOwnerId)
                && !Objects.equals(sessionOwnerId, effectiveUserId)) {
            return AuthorizationDecision.deny("abac_deny", "session_owner_mismatch", actualRoles, attributes);
        }

        if (("flight_booking".equals(workflowCode) || "hotel_booking".equals(workflowCode))
                && !Boolean.TRUE.equals(attributes.get("travel_scope"))) {
            return AuthorizationDecision.deny("abac_deny", "travel_scope_required", actualRoles, attributes);
        }

        if (isHighRiskTool(requestedToolCode) && !Boolean.TRUE.equals(attributes.get("high_risk_allowed"))) {
            return AuthorizationDecision.deny("abac_deny", "high_risk_requires_trusted_user", actualRoles, attributes);
        }

        return AuthorizationDecision.allow("policy_allow_execution", actualRoles, attributes);
    }

    public void requireWorkflowAdminAction(String userId, Long workspaceId, String workflowCode, String action) {
        String effectiveUserId = normalizeUserId(userId);
        Long effectiveWorkspaceId = workspaceId == null ? 1L : workspaceId;
        Set<String> actualRoles = loadRoles(effectiveUserId, effectiveWorkspaceId);
        if (!actualRoles.contains("workflow_admin")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Permission denied for user: " + effectiveUserId);
        }

        Map<String, Object> attributes = resolveUserAttributes(effectiveUserId, actualRoles, workflowCode, null, null);
        if (!Boolean.TRUE.equals(attributes.get("admin_change_allowed"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ABAC denied workflow action: " + action);
        }
    }

    public boolean isHighRiskTool(String toolCode) {
        return toolCode != null && HIGH_RISK_TOOLS.contains(toolCode);
    }

    private Set<String> loadRoles(String userId, Long workspaceId) {
        return userRoleRepository.findByIdUserIdAndIdWorkspaceId(userId, workspaceId)
                .stream()
                .map(UserRole::getRole)
                .filter(role -> role != null && role.getCode() != null)
                .map(role -> role.getCode().toLowerCase())
                .collect(Collectors.toSet());
    }

    private Map<String, Object> resolveUserAttributes(
            String userId,
            Set<String> actualRoles,
            String workflowCode,
            String requestedToolCode,
            String sessionOwnerId
    ) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("user_id", userId);
        attributes.put("workflow_code", workflowCode);
        attributes.put("requested_tool_code", requestedToolCode);
        attributes.put("session_owner_id", sessionOwnerId);
        attributes.put("session_owner_match",
                sessionOwnerId == null || sessionOwnerId.isBlank() || Objects.equals(sessionOwnerId, userId));

        boolean isAnonymous = "anonymous".equalsIgnoreCase(userId);
        boolean isWorkflowAdmin = actualRoles.contains("workflow_admin");
        boolean isViewer = actualRoles.contains("viewer");
        String trustLevel = isWorkflowAdmin ? "high" : (isViewer ? "medium" : "low");

        attributes.put("trust_level", trustLevel);
        attributes.put("travel_scope", !isAnonymous);
        attributes.put("high_risk_allowed", !isAnonymous && !"low".equals(trustLevel));
        attributes.put("business_hours", isBusinessHours());
        attributes.put("admin_change_allowed", isWorkflowAdmin && !isAnonymous);
        return attributes;
    }

    private boolean isBusinessHours() {
        LocalTime now = LocalTime.now();
        return !now.isBefore(LocalTime.of(6, 0)) && !now.isAfter(LocalTime.of(23, 0));
    }

    private String normalizeUserId(String userId) {
        return userId == null || userId.isBlank() ? "anonymous" : userId;
    }

    public record AuthorizationDecision(
            boolean allowed,
            String effect,
            String reason,
            Set<String> roles,
            Map<String, Object> attributes
    ) {
        static AuthorizationDecision allow(String reason, Set<String> roles, Map<String, Object> attributes) {
            return new AuthorizationDecision(true, "allow", reason, roles, attributes);
        }

        static AuthorizationDecision deny(String effect, String reason, Set<String> roles, Map<String, Object> attributes) {
            return new AuthorizationDecision(false, effect, reason, roles, attributes);
        }
    }
}
