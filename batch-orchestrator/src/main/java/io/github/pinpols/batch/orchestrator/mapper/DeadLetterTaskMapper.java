package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.common.enums.DeadLetterReplayStatus;
import io.github.pinpols.batch.orchestrator.domain.entity.DeadLetterTaskEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface DeadLetterTaskMapper {

  int insert(DeadLetterTaskEntity entity);

  /** 积压量指标：统计指定 replay_status 的死信总数（跨租户）。 */
  long countByReplayStatuses(@Param("statuses") List<String> statuses);

  DeadLetterTaskEntity selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  int markReplaying(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("expectedStatus") String expectedStatus,
      @Param("targetStatus") String targetStatus);

  int markReplaySuccess(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("successStatus") String successStatus,
      @Param("replayCount") Integer replayCount,
      @Param("lastReplayAt") Instant lastReplayAt,
      @Param("lastReplayResult") String lastReplayResult);

  int markReplayFailure(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("targetStatus") String targetStatus,
      @Param("replayCount") Integer replayCount,
      @Param("lastReplayAt") Instant lastReplayAt,
      @Param("lastReplayResult") String lastReplayResult,
      @Param("nextReplayAt") Instant nextReplayAt);

  /** V90: 选出自动重放到期 + 未达上限 + SYSTEM 类的死信记录。 */
  default List<DeadLetterTaskEntity> selectDueAutoRetries(int batchSize) {
    return selectDueAutoRetriesByStatuses(
        batchSize,
        List.of(DeadLetterReplayStatus.NEW.code(), DeadLetterReplayStatus.FAILED.code()));
  }

  List<DeadLetterTaskEntity> selectDueAutoRetriesByStatuses(
      @Param("batchSize") int batchSize, @Param("replayStatuses") List<String> replayStatuses);

  /** V90: 自动重放达到 max_replay_count 后，关闭自动重放，转 GIVE_UP（人工仍可触发 /internal/dead-letters/{id}/replay）。 */
  default int markGiveUp(String tenantId, Long id) {
    return updateGiveUp(tenantId, id, DeadLetterReplayStatus.GIVE_UP.code());
  }

  int updateGiveUp(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("targetStatus") String targetStatus);
}
