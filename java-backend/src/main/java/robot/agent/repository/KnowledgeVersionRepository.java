package robot.agent.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import robot.agent.model.KnowledgeVersion;

import java.util.List;
import java.util.Optional;

public interface KnowledgeVersionRepository extends JpaRepository<KnowledgeVersion, Long> {
    Optional<KnowledgeVersion> findByKbCodeAndVersion(String kbCode, String version);

    List<KnowledgeVersion> findByKbCodeOrderByCreatedAtDesc(String kbCode);
}
