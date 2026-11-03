package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.service.batchday.BatchDayOpenService;
import io.github.pinpols.batch.orchestrator.domain.entity.BusinessCalendarEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.infrastructure.metrics.BatchDayCatchupMetrics;
import io.github.pinpols.batch.orchestrator.mapper.BusinessCalendarMapper;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 主动打开批次日实例的调度入口：负责触发、分布式锁、停机排空、逐日历 RLS 绑定与短事务及扫描指标。
 *
 * <p>开日业务步骤在 {@link BatchDayOpenService}；本调度器让日切后的 {@code batch_day_instance} 在没有作业触发时也先成为可观察、可门闩判断的一等运行对象。
 */
@Component
@RequiredArgsConstructor
public class BatchDayOpenScheduler {

  private final BusinessCalendarMapper businessCalendarMapper;
  private final OrchestratorGracefulShutdown gracefulShutdown;
  private final BatchDateTimeSupport dateTimeSupport;
  private final BatchDayCatchupMetrics catchupMetrics;
  private final BatchDayOpenService batchDayOpenService;
  // 事务边界：扫描循环本身不开事务（避免长事务 + 与 @SchedulerLock AOP 顺序歧义），
  // 每个 calendar 通过 TransactionTemplate 使用 short tx 收敛依赖等待、batch_day_instance 和审计日志。
  private final PlatformTransactionManager transactionManager;

  @Scheduled(fixedDelayString = "${batch.batch-day.open-scan-interval-millis:60000}")
  @SchedulerLock(name = "batch_day_open", lockAtMostFor = "PT2M", lockAtLeastFor = "PT15S")
  public void scheduledOpen() {
    openDueBatchDays(dateTimeSupport.nowInstant());
  }

  public void openDueBatchDays(Instant now) {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    catchupMetrics.beginScan();
    List<BusinessCalendarEntity> calendars = businessCalendarMapper.selectByEnabled(true);
    if (calendars == null || calendars.isEmpty()) {
      catchupMetrics.completeScan();
      return;
    }
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    try {
      for (BusinessCalendarEntity calendar : calendars) {
        if (EmptyChecks.isNull(calendar) || !Texts.hasText(calendar.tenantId())) {
          // openOne 内部也校验,这里先过滤一道,避免空 tenant 触发 holder NPE 路径。
          continue;
        }
        // RLS Phase B：openOne 的所有 mapper（batch_day_instance / calendar_dependency /
        // disaster_day_override / job_execution_log）都依赖租户上下文；绑定要包住整个 tx，让 short tx
        // 的所有写入都看到 app.tenant_id。
        long calendarLagDays = RlsTenantContextHolder.runWithTenant(
            calendar.tenantId(),
            () ->
                // 每个 calendar 一笔 short tx：依赖等待、批次日实例和审计日志在同事务收敛。
                tx.execute(status -> batchDayOpenService.openCalendar(calendar, now)));
        catchupMetrics.recordCalendarLag(calendarLagDays);
      }
      catchupMetrics.completeScan();
    } catch (RuntimeException failure) {
      catchupMetrics.failScan();
      throw failure;
    }
  }
}
