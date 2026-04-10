package robot.agent.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import robot.agent.model.UserRole;
import robot.agent.model.UserRoleId;

import java.util.List;

public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> {
    List<UserRole> findByIdUserIdAndIdWorkspaceId(String userId, Long workspaceId);
}
