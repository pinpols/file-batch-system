package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.common.rls.RlsTenantContextHolder;
import com.example.batch.orchestrator.domain.entity.BatchDayInstanceEntity;
import com.example.batch.orchestrator.domain.entity.BatchDayWaitingLaunchEntity;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.mapper.BatchDayInstanceMapper;
import com.example.batch.orchestrator.mapper.BatchDayWaitingLaunchMapper;
import com.example.batch.orchestrator.service.BatchDayOperationService;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 批量日等待释放调度器。
 *
 * <p>定时扫描 {@code batch_day_waiting_launch} 中 {@code WAITING} 行,对每行的"前一日"批量日做状态探测; 若前一日已进入 {@code
 * SETTLED / SKIPPED / MANUAL_RELEASED}(与 {@link
 * com.example.batch.orchestrator.service.BatchDayGateService} 判定可放行的口径一致), 则委托 {@link
 * BatchDayOperationService#releaseWaitingLaunchesForBatchDay} 重投 launch + markReleased。
 *
 * <p>设计理由:{@link BatchDaySettleScheduler} 把 day 自动推到 SETTLED 但不会主动释放等待中的后一日触发, 之前只能靠人工 {@code
 * RELEASE} 操作触发释放,等价于"自动批量行为根本跑不通"。本调度器把这条链补齐:
 *
 * <ol>
 *   <li>scheduler 扫 WAITING 行(全租户,单 batch ≤ {@link #WAITING_SCAN_LIMIT})
 *   <li>按 (tenantId, calendarCode, previousBizDate) 去重 → 一组前一日只查一次状态
 *   <li>前一日落在 {@link #RELEASABLE_PREVIOUS_DAY_STATUSES} 时委派给 operation service 重投 launch
 *   <li>operator 标记为 {@link #AUTO_RELEASE_OPERATOR},审计可与人工 RELEASE 区分
 * </ol>
 *
 * <p>异常隔离:单租户 / 单 day 释放抛错不影响其它行,下一 tick 自然重试。 ShedLock {@code batch_day_waiting_release}
 * 保证多实例只有一台执行。 graceful shutdown 期间静默跳过。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchDayWaitingReleaseScheduler {

  /**
   * 与 {@link com.example.batch.orchestrator.service.BatchDayGateService#isPreviousDayReleasable}
   * 同义。
   */
  static final Set<String> RELEASABLE_PREVIOUS_DAY_STATUSES =
      Set.of("SETTLED", "SKIPPED", "MANUAL_RELEASED");

  /** 单 tick 扫描的 WAITING 行数上限,防一次性扫表打挂。 */
  static final int WAITING_SCAN_LIMIT = 500;

  static final String AUTO_RELEASE_OPERATOR = "AUTO_RELEASE";

  private final BatchDayWaitingLaunchMapper waitingLaunchMapper;
  private final BatchDayInstanceMapper batchDayInstanceMapper;
  private final BatchDayOperationService batchDayOperationService;
  private final OrchestratorGracefulShutdown gracefulShutdown;

  @Scheduled(fixedDelayString = "${batch.batch-day.waiting-release-scan-interval-millis:60000}")
  @SchedulerLock(
      name = "batch_day_waiting_release",
      lockAtMostFor = "PT3M",
      lockAtLeastFor = "PT30S")
  public void scheduledRelease() {
    release();
  }

  /** 扫描入口 — 暴露给测试直接调用,scheduled 包装层只负责 lock + draining gate。 */
  public void release() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    List<BatchDayWaitingLaunchEntity> waiting =
        waitingLaunchMapper.selectWaiting(null, WAITING_SCAN_LIMIT);
    if (waiting == null || waiting.isEmpty()) {
      return;
    }
    Set<PreviousDayKey> seen = new HashSet<>();
    int releasedTotal = 0;
    int previousDaysChecked = 0;
    for (BatchDayWaitingLaunchEntity entity : waiting) {
      if (entity == null || entity.bizDate() == null) {
        continue;
      }
      String tenantId = entity.tenantId();
      if (tenantId == null || tenantId.isBlank()) {
        continue;
      }
      LocalDate previousBizDate = entity.bizDate().minusDays(1);
      PreviousDayKey key =
          new PreviousDayKey(entity.tenantId(), entity.calendarCode(), previousBizDate);
      if (!seen.add(key)) {
        // 同一前一日已处理过(或已确认未放行),本轮跳过
        continue;
      }
      previousDaysChecked++;
      // RLS Phase B：previousDay 查询 + releaseWaitingLaunchesForBatchDay 内部 launch 重投均要带租户。
      // try/catch 仍包在 runWithTenant 外，保留"单 day 异常隔离 → 不影响其它 key"语义，且 holder finally 清理。
      Integer releasedCount = null;
      try {
        releasedCount =
            RlsTenantContextHolder.runWithTenant(
                tenantId,
                () -> {
                  BatchDayInstanceEntity previousDay =
                      batchDayInstanceMapper.selectByTenantCalendarBizDate(
                          entity.tenantId(), entity.calendarCode(), previousBizDate);
                  if (previousDay == null || !isReleasable(previousDay.dayStatus())) {
                    return 0;
                  }
                  int released =
                      batchDayOperationService.releaseWaitingLaunchesForBatchDay(
                          previousDay, AUTO_RELEASE_OPERATOR);
                  if (released > 0) {
                    log.info(
                        "auto-released {} waiting launch(es): tenantId={}, calendarCode={},"
                            + " previousBizDate={}",
                        released,
                        previousDay.tenantId(),
                        previousDay.calendarCode(),
                        previousBizDate);
                  }
                  return released;
                });
      } catch (Exception ex) {
        // 单 day 异常隔离 — 不影响其它 key,下 tick 重试
        log.warn(
            "auto-release failed: tenantId={}, calendarCode={}, previousBizDate={}, msg={}",
            entity.tenantId(),
            entity.calendarCode(),
            previousBizDate,
            ex.getMessage());
      }
      if (releasedCount != null) {
        releasedTotal += releasedCount;
      }
    }
    if (releasedTotal > 0 || previousDaysChecked > 0) {
      log.debug(
          "waiting release tick: scanned={}, previousDaysChecked={}, releasedTotal={}",
          waiting.size(),
          previousDaysChecked,
          releasedTotal);
    }
  }

  private static boolean isReleasable(String status) {
    if (status == null) {
      return false;
    }
    return RELEASABLE_PREVIOUS_DAY_STATUSES.contains(status.trim().toUpperCase(Locale.ROOT));
  }

  /** 用于按"前一日"去重的复合 key,避免一个 batch_day 内多条 waiting 重复探测/释放。 */
  private record PreviousDayKey(String tenantId, String calendarCode, LocalDate previousBizDate) {
    PreviousDayKey {
      Objects.requireNonNull(tenantId, "tenantId");
      Objects.requireNonNull(calendarCode, "calendarCode");
      Objects.requireNonNull(previousBizDate, "previousBizDate");
    }
  }
}
