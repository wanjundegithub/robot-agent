package robot.agent.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import robot.agent.model.LlmModelRecord;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LlmModelRecordRepository extends JpaRepository<LlmModelRecord, Long> {

    Optional<LlmModelRecord> findByModelCode(String modelCode);

    List<LlmModelRecord> findByModelCodeIn(Collection<String> modelCodes);

    List<LlmModelRecord> findByProviderCodeOrderByUpdatedAtDesc(String providerCode);

    long countByProviderCode(String providerCode);

    @Query("""
            select modelRecord
            from LlmModelRecord modelRecord
            left join LlmProviderConfig provider
                on provider.providerCode = modelRecord.providerCode
            where (:keyword is null
                or lower(modelRecord.modelCode) like lower(concat('%', :keyword, '%'))
                or lower(modelRecord.modelName) like lower(concat('%', :keyword, '%'))
                or lower(provider.providerType) like lower(concat('%', :keyword, '%')))
              and (:providerCode is null or modelRecord.providerCode = :providerCode)
              and (:enabled is null or modelRecord.enabled = :enabled)
            """)
    Page<LlmModelRecord> search(
            @Param("keyword") String keyword,
            @Param("providerCode") String providerCode,
            @Param("enabled") Boolean enabled,
            Pageable pageable
    );
}
