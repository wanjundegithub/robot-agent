package robot.agent.apicenter.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import robot.agent.apicenter.model.ApiAuthConfig;
import robot.agent.apicenter.model.ApiAuthScopeType;

import java.util.Optional;

public interface ApiAuthConfigRepository extends JpaRepository<ApiAuthConfig, Long> {
    Optional<ApiAuthConfig> findByScopeTypeAndScopeId(ApiAuthScopeType scopeType, Long scopeId);
    void deleteByScopeTypeAndScopeId(ApiAuthScopeType scopeType, Long scopeId);
}
