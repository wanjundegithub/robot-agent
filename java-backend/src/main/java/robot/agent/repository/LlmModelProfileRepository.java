package robot.agent.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import robot.agent.model.LlmModelProfile;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LlmModelProfileRepository extends JpaRepository<LlmModelProfile, Long> {
    Optional<LlmModelProfile> findByProfileCode(String profileCode);
    List<LlmModelProfile> findByProfileCodeIn(Collection<String> profileCodes);
    List<LlmModelProfile> findByEnabledTrueOrderByProfileCodeAsc();
}
