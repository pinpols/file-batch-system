package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplaySessionEntity;
import io.github.pinpols.batch.orchestrator.domain.param.BatchDayReplaySessionUpdateCountsParam;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateBatchDayReplaySessionStatusParam;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** ADR-020 batch_day_replay_session MyBatis 映射。 */
public interface BatchDayReplaySessionMapper {

  int insert(BatchDayReplaySessionEntity entity);

  BatchDayReplaySessionEntity selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  /** 在报告事务中串行化终态条目的记账操作。 */
  BatchDayReplaySessionEntity selectForUpdate(
      @Param("tenantId") String tenantId, @Param("id") Long id);

  /** 找 (tenant, calendarCode, bizDate) 的 active session（PENDING_APPROVAL / RUNNING）；无则返回 null。 */
  BatchDayReplaySessionEntity selectActiveByCalendarBizDate(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate);

  /** 推进 status；仅当当前 status 在期望集合里才生效，避免并发抢占覆写。 */
  int updateStatus(UpdateBatchDayReplaySessionStatusParam param);

  default int reopenPartialFailedForRetry(String tenantId, Long id, Instant now) {
    String targetStatus = "RUNNING";
    return reopenPartialFailedForRetryWithStatus(tenantId, id, now, targetStatus);
  }

  int reopenPartialFailedForRetryWithStatus(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("now") Instant now,
      @Param("targetStatus") String targetStatus);

  /** 写计数（succeeded/failed/inFlight/total）。 */
  int updateCounts(BatchDayReplaySessionUpdateCountsParam parameters);

  /** 列出 RUNNING 状态的 session，dispatcher / reconciler 用。 */
  List<BatchDayReplaySessionEntity> selectByStatus(
      @Param("status") String status, @Param("limit") int limit);

  /** UI 历史列表：按租户倒序查看最近创建的 replay session。 */
  List<BatchDayReplaySessionEntity> selectRecentByTenant(
      @Param("tenantId") String tenantId,
      @Param("status") String status,
      @Param("limit") int limit);

  /** Date-range operation children in stable business-date order. */
  List<BatchDayReplaySessionEntity> selectByReplayOperation(
      @Param("tenantId") String tenantId, @Param("replayOperationId") Long replayOperationId);
}
