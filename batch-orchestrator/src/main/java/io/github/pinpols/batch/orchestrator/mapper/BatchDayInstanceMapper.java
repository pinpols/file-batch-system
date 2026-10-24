package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayInstanceEntity;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * {@code batch.batch_day_instance} MyBatis 映射（替代原 Spring Data JDBC {@code
 * BatchDayInstanceRepository}，对齐 docs/agent-baseline.md §架构硬约束 — 运行态走 MyBatis）。
 *
 * <p>关键约束：
 *
 * <ul>
 *   <li>{@link #insert(BatchDayInstanceEntity)} 用 {@code ON CONFLICT (tenant_id, calendar_code,
 *       biz_date) DO NOTHING}：并发首次创建只一行写入数据库；caller 拿不到 id 也无所谓，下一轮 {@link
 *       #selectByTenantCalendarBizDate} 自然读到。
 *   <li>{@link #updateWithCas(BatchDayInstanceEntity)} 走 {@code WHERE tenant_id=? AND id=? AND version=?}
 *       CAS：返回值 0
 *       视为乐观锁冲突，调用方应抛 {@code OptimisticLockingFailureException} 保留 SDJ 时代行为。
 * </ul>
 */
public interface BatchDayInstanceMapper {

  /**
   * 首次插入。{@code ON CONFLICT (uk_batch_day_instance) DO NOTHING}：并发竞争时只一行成功，无需特殊处理 UV。
   *
   * @return 实际写入行数（0 表示并发已被另一节点抢先创建）
   */
  int insert(BatchDayInstanceEntity entity);

  /**
   * CAS 更新：必须 tenantId + id + version 都匹配才生效。{@code SET version = version + 1}。
   *
   * @return 影响行数；0 表示 version 冲突 → 调用方抛 {@code OptimisticLockingFailureException}
   */
  int updateWithCas(BatchDayInstanceEntity entity);

  /** 按租户 + 日历 + 业务日（unique constraint 三元组）查询当前行。 */
  BatchDayInstanceEntity selectByTenantCalendarBizDate(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate);

  /** 最近一个不晚于当前日期的批量日，用于按日期顺序恢复调度器停机期间的缺口。 */
  LocalDate selectLatestBizDateAtOrBefore(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate);

  List<LocalDate> selectBizDatesInRange(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("fromDate") LocalDate fromDate,
      @Param("throughDate") LocalDate throughDate);

  int initializeOpenCursor(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("lastScannedBizDate") LocalDate lastScannedBizDate);

  LocalDate selectOpenCursorForUpdate(
      @Param("tenantId") String tenantId, @Param("calendarCode") String calendarCode);

  LocalDate selectOpenCursorDate(
      @Param("tenantId") String tenantId, @Param("calendarCode") String calendarCode);

  int advanceOpenCursor(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("expectedBizDate") LocalDate expectedBizDate,
      @Param("nextBizDate") LocalDate nextBizDate,
      @Param("scannedAt") java.time.Instant scannedAt);

  /** 扫描指定状态集合的批次日实例（用于 cutoff / settle 调度器候选过滤）。 */
  List<BatchDayInstanceEntity> selectByDayStatusIn(
      @Param("dayStatuses") Collection<String> dayStatuses);

  /** Failed days whose configured catch-up still has an unaccepted or retryable request. */
  List<BatchDayInstanceEntity> selectFailedDaysNeedingCatchUp(@Param("limit") int limit);

  /** Counts unaccounted scheduled Quartz occurrences before allowing an empty-day settlement. */
  int countBlockingScheduledOccurrences(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate);

  int countUncoveredScheduledDefinitions(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("cutoffAt") java.time.Instant cutoffAt);

  /** Event/mixed-trigger jobs lack a per-day expected-work ledger, so automation must fail closed. */
  int countUntrackedTriggerDefinitions(
      @Param("tenantId") String tenantId, @Param("calendarCode") String calendarCode);
}
