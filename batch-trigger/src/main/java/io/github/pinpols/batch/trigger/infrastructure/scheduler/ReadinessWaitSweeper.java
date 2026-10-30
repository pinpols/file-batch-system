package io.github.pinpols.batch.trigger.infrastructure.scheduler;

import io.github.pinpols.batch.common.enums.ScheduledOccurrenceStatus;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.trigger.domain.query.ExpiredReadinessWaitRow;
import io.github.pinpols.batch.trigger.infrastructure.TriggerGracefulShutdown;
import io.github.pinpols.batch.trigger.mapper.ScheduledOccurrenceMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * readiness 持久等待的兜底扫尾器。
 *
 * <p>等待窗口随 {@code scheduled_occurrence.readiness_deadline} 落库后，即使 Quartz 的 readiness-retry
 * 回调丢失，也能在超窗时把等待终结为 {@link ScheduledOccurrenceStatus#READINESS_TIMEOUT}，结束触发侧义务。
 * 与 Quartz 竞争同一 occurrence 时用状态 CAS（WAITING_READINESS → READINESS_TIMEOUT）保证只推进一次。
 *
 * <p>本类只收敛 Trigger occurrence。Orchestrator 的持久等待由其恢复器独立推进，不能仅据本次
 * CAS 成功推定另一侧已完成移交或业务已经启动。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadinessWaitSweeper {

  private static final int SWEEP_BATCH_SIZE = 100;
  private static final String METRIC_TIMEOUT = "batch.trigger.readiness.wait.timeout.total";

  private final ScheduledOccurrenceMapper occurrenceMapper;
  private final TriggerGracefulShutdown gracefulShutdown;
  private final MeterRegistry meterRegistry;

  @Scheduled(fixedDelayString = "${batch.trigger.readiness-wait-sweep-interval-millis:60000}")
  @SchedulerLock(
      name = "trigger_readiness_wait_sweeper",
      lockAtMostFor = "PT2M",
      lockAtLeastFor = "PT5S")
  public void sweep() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    Instant sweepAt = BatchDateTimeSupport.utcNow();
    List<ExpiredReadinessWaitRow> expiredWaits =
        occurrenceMapper.selectExpiredReadinessWaits(sweepAt, SWEEP_BATCH_SIZE);
    if (EmptyChecks.isEmpty(expiredWaits)) {
      return;
    }
    int timedOut = 0;
    for (ExpiredReadinessWaitRow expiredWait : expiredWaits) {
      int transitioned = occurrenceMapper.timeoutExpiredReadinessWait(expiredWait, sweepAt);
      if (transitioned == 1) {
        timedOut++;
        meterRegistry.counter(METRIC_TIMEOUT).increment();
        log.error(
            "readiness wait exceeded its window without a Quartz callback; scheduled fire is given"
                + " up: tenantId={}, workflowDefinitionId={}, scheduledFireTime={}, deadline={}",
            expiredWait.getTenantId(),
            expiredWait.getWorkflowDefinitionId(),
            expiredWait.getScheduledFireTime(),
            expiredWait.getDeadline());
      }
    }
    if (timedOut > 0) {
      log.info("readiness wait sweep completed: timedOut={}", timedOut);
    }
  }
}
