package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplaySessionEntity;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** ADR-020 batch_day_replay_session MyBatis 映射。 */
@SuppressWarnings("PMD.ExcessiveParameterList")
public interface BatchDayReplaySessionMapper {

  int insert(BatchDayReplaySessionEntity record);

  BatchDayReplaySessionEntity selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  /** 找 (tenant, calendarCode, bizDate) 的 active session（PENDING_APPROVAL / RUNNING）；无则返回 null。 */
  BatchDayReplaySessionEntity selectActiveByCalendarBizDate(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate);

  /** 推进 status；仅当当前 status 在期望集合里才生效，避免并发抢占覆写。 */
  int updateStatus(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("toStatus") String toStatus,
      @Param("expectedStatuses") List<String> expectedStatuses,
      @Param("startedAt") Instant startedAt,
      @Param("completedAt") Instant completedAt,
      @Param("approvedBy") String approvedBy,
      @Param("now") Instant now);

  /** 写计数（succeeded/failed/inFlight/total）。 */
  int updateCounts(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("succeededCount") Integer succeededCount,
      @Param("failedCount") Integer failedCount,
      @Param("inFlightCount") Integer inFlightCount,
      @Param("totalCount") Integer totalCount,
      @Param("now") Instant now);

  /** 列出 RUNNING 状态的 session，dispatcher / reconciler 用。 */
  List<BatchDayReplaySessionEntity> selectByStatus(
      @Param("status") String status, @Param("limit") int limit);
}
