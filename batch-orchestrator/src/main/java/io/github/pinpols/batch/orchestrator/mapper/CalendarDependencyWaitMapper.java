package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.param.CalendarDependencyManualActionParam;
import io.github.pinpols.batch.orchestrator.domain.entity.CalendarDependencyWaitEntity;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 跨日历依赖等待的持久化与并发领取。 */
public interface CalendarDependencyWaitMapper {

  String setTenantContext(@Param("tenantId") String tenantId);

  /** 首次写入等待快照，后续重检只更新原因/计数，不漂移业务日和 cutoff 快照。 */
  int upsertWaiting(CalendarDependencyWaitEntity entity);

  CalendarDependencyWaitEntity selectByTenantCalendarBizDate(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate);

  int countWaitingByTenantCalendarBizDate(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate);

  /** 同租户/日历下领取有限 WAITING 行；数据库行锁与 SKIP LOCKED 防止多个调度器重复处理。 */
  List<CalendarDependencyWaitEntity> selectWaitingForUpdate(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("checkedBefore") Instant checkedBefore,
      @Param("limit") int limit);

  int updateCheckResult(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("blockedReason") String blockedReason,
      @Param("checkedAt") Instant checkedAt);

  int updateStatus(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("waitStatus") String waitStatus,
      @Param("openedAt") Instant openedAt);

  int claimTimeoutAlert(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("alertedAt") Instant alertedAt);

  List<CalendarDependencyWaitEntity> selectRecent(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("waitStatus") String waitStatus,
      @Param("limit") int limit);

  int requestManualAction(CalendarDependencyManualActionParam param);
}
