package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplayPreviewTokenEntity;
import java.time.Instant;
import org.apache.ibatis.annotations.Param;

/** 批次日重放预览凭证映射；消费路径在 PostgreSQL 行锁内完成。 */
public interface BatchDayReplayPreviewTokenMapper {

  int insert(BatchDayReplayPreviewTokenEntity entity);

  BatchDayReplayPreviewTokenEntity selectForUpdate(
      @Param("tenantId") String tenantId, @Param("tokenHash") String tokenHash);

  int consume(@Param("tenantId") String tenantId, @Param("tokenHash") String tokenHash);

  int linkSession(
      @Param("tenantId") String tenantId,
      @Param("tokenHash") String tokenHash,
      @Param("sessionId") Long sessionId);

  /** 系统维护任务：按过期时间有界清理，不参与用户业务查询。 */
  int deleteExpired(
      @Param("now") Instant now,
      @Param("consumedBefore") Instant consumedBefore,
      @Param("limit") int limit);
}
