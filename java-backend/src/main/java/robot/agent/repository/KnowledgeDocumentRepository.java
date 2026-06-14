package robot.agent.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import robot.agent.model.KnowledgeDocument;

import java.util.List;
import java.util.Optional;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {
    List<KnowledgeDocument> findByKbCodeAndVersionOrderByCreatedAtDesc(String kbCode, String version);

    List<KnowledgeDocument> findByKbCodeOrderByCreatedAtDesc(String kbCode);

    Optional<KnowledgeDocument> findByDocId(String docId);
}
