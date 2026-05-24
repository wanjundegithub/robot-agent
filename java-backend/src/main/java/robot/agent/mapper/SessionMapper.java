package robot.agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface SessionMapper {


    @Update("UPDATE `session` SET status = #{status}, last_activity_at = #{lastActivityAt} WHERE id = #{sessionId}")
    int markSessionStatus(
            @Param("sessionId") String sessionId,
            @Param("status") String status,
            @Param("lastActivityAt") LocalDateTime lastActivityAt
    );
}
