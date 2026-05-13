package robot.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import robot.agent.common.ApplicationConstants;
import robot.agent.model.KnowledgeBase;
import robot.agent.model.KnowledgeBaseStatus;
import robot.agent.model.KnowledgeVersion;
import robot.agent.model.KnowledgeVersionStatus;
import robot.agent.model.Role;
import robot.agent.model.UserRole;
import robot.agent.model.UserRoleId;
import robot.agent.repository.KnowledgeBaseRepository;
import robot.agent.repository.KnowledgeVersionRepository;
import robot.agent.repository.RoleRepository;
import robot.agent.repository.UserRoleRepository;

import java.time.LocalDateTime;
import java.util.Map;

@Component
public class DemoWorkflowDataInitializer implements ApplicationRunner {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeVersionRepository knowledgeVersionRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final ObjectMapper objectMapper;

    public DemoWorkflowDataInitializer(
            KnowledgeBaseRepository knowledgeBaseRepository,
            KnowledgeVersionRepository knowledgeVersionRepository,
            RoleRepository roleRepository,
            UserRoleRepository userRoleRepository,
            ObjectMapper objectMapper
    ) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.knowledgeVersionRepository = knowledgeVersionRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        seedRoles();
        seedKnowledgeBase();
    }

    private void seedRoles() {
        createRoleIfMissing("workflow_admin", "Workflow Admin", "Can manage workflow definitions and versions.");
        createRoleIfMissing("knowledge_admin", "Knowledge Admin", "Can manage knowledge bases and versions.");
        createRoleIfMissing("viewer", "Viewer", "Read-only access to workflow resources.");

        assignRoleIfMissing("demo-admin", ApplicationConstants.DEFAULT_WORKSPACE_ID, "workflow_admin");
        assignRoleIfMissing("demo-admin", ApplicationConstants.DEFAULT_WORKSPACE_ID, "knowledge_admin");
        assignRoleIfMissing("demo-admin", ApplicationConstants.DEFAULT_WORKSPACE_ID, "viewer");
        assignRoleIfMissing("demo-user", ApplicationConstants.DEFAULT_WORKSPACE_ID, "viewer");
    }

    private void createRoleIfMissing(String code, String name, String description) {
        if (roleRepository.findByCode(code).isPresent()) {
            return;
        }
        Role role = new Role();
        role.setCode(code);
        role.setName(name);
        role.setDescription(description);
        roleRepository.save(role);
    }

    private void assignRoleIfMissing(String userId, Long workspaceId, String roleCode) {
        Role role = roleRepository.findByCode(roleCode)
                .orElseThrow(() -> new IllegalStateException("Role not found: " + roleCode));
        UserRoleId userRoleId = new UserRoleId();
        userRoleId.setUserId(userId);
        userRoleId.setWorkspaceId(workspaceId);
        userRoleId.setRoleId(role.getId());
        if (userRoleRepository.existsById(userRoleId)) {
            return;
        }
        UserRole userRole = new UserRole();
        userRole.setId(userRoleId);
        userRole.setRole(role);
        userRoleRepository.save(userRole);
    }

    private void seedKnowledgeBase() {
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findByKbCode("flight_policy_kb")
                .orElseGet(() -> {
                    KnowledgeBase base = new KnowledgeBase();
                    base.setWorkspaceId(ApplicationConstants.DEFAULT_WORKSPACE_ID);
                    base.setKbCode("flight_policy_kb");
                    base.setName("Flight Policy KB");
                    base.setDescription("Flight booking and change policy knowledge base.");
                    base.setEmbeddingModel("demo-embedding-model");
                    base.setStatus(KnowledgeBaseStatus.ACTIVE);
                    base.setCreatedBy("system");
                    base.setCreatedAt(LocalDateTime.now());
                    return knowledgeBaseRepository.save(base);
                });

        if (knowledgeVersionRepository.findByKbCodeAndVersion("flight_policy_kb", "1.0.0").isEmpty()) {
            KnowledgeVersion version = new KnowledgeVersion();
            version.setKbCode("flight_policy_kb");
            version.setVersion("1.0.0");
            version.setStatus(KnowledgeVersionStatus.PUBLISHED);
            version.setChunkCount(4);
            version.setDocCount(2);
            version.setCreatedBy("system");
            version.setCreatedAt(LocalDateTime.now());
            version.setPublishedAt(LocalDateTime.now());
            knowledgeVersionRepository.save(version);
        }

        knowledgeBase.setCurrentVersion("1.0.0");
        knowledgeBaseRepository.save(knowledgeBase);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize seed payload", exception);
        }
    }
}
