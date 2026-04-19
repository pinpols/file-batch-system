package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.DeadLetterTaskEntity;
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
      @Param("lastReplayResult") String lastReplayResult);
}
