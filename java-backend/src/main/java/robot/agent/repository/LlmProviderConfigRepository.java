package robot.agent.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import robot.agent.model.LlmProviderConfig;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LlmProviderConfigRepository extends JpaRepository<LlmProviderConfig, Long> {
    Optional<LlmProviderConfig> findByProviderCode(String providerCode);
    List<LlmProviderConfig> findByProviderCodeIn(Collection<String> providerCodes);
    List<LlmProviderConfig> findByEnabledTrueOrderByProviderCodeAsc();
    List<LlmProviderConfig> findAllByOrderByProviderCodeAsc();
}
