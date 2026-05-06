package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.BatchDayReplayEntryEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** ADR-020 batch_day_replay_entry MyBatis 映射。 */
public interface BatchDayReplayEntryMapper {

  int insert(BatchDayReplayEntryEntity record);

  /** 批量插入；MyBatis foreach 拼 INSERT 多 VALUES。 */
  int insertBatch(@Param("records") List<BatchDayReplayEntryEntity> records);

  List<BatchDayReplayEntryEntity> selectBySessionId(@Param("sessionId") Long sessionId);

  List<BatchDayReplayEntryEntity> selectBySessionAndStatus(
      @Param("sessionId") Long sessionId,
      @Param("status") String status,
      @Param("limit") int limit);

  /** 根据 rerun_instance_id 反查（terminal 回填用）。 */
  BatchDayReplayEntryEntity selectByRerunInstanceId(
      @Param("tenantId") String tenantId, @Param("rerunInstanceId") Long rerunInstanceId);

  /** 推进 status + 关联字段。 */
  int updateStatus(
      @Param("id") Long id,
      @Param("toStatus") String toStatus,
      @Param("rerunInstanceId") Long rerunInstanceId,
      @Param("resultVersionId") Long resultVersionId,
      @Param("failureReason") String failureReason,
      @Param("startedAt") Instant startedAt,
      @Param("finishedAt") Instant finishedAt,
      @Param("now") Instant now);

  /** 用于 session 完成判断：每个状态各有多少 entry。 */
  long countBySessionAndStatus(@Param("sessionId") Long sessionId, @Param("status") String status);
}
