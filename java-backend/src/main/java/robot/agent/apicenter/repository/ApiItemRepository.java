package robot.agent.apicenter.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import robot.agent.apicenter.model.ApiItem;

import java.util.List;

public interface ApiItemRepository extends JpaRepository<ApiItem, Long> {
    long countByGroupId(Long groupId);
    List<ApiItem> findByGroupIdOrderByUpdatedAtDesc(Long groupId);
    void deleteByGroupId(Long groupId);
}
