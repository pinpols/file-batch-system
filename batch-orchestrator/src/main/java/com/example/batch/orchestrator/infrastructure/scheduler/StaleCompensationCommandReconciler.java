package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.common.enums.CompensationCommandStatus;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.mapper.CompensationCommandMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 对账那些在「命令插入」与「终态更新」之间发生 JVM 崩溃、被遗留为 RUNNING 的补偿命令。
 *
 * <p>无需事务包裹：{@link CompensationCommandMapper#markStaleRunningFailed} 是单条带 LIMIT 的批量 UPDATE，
 * 在数据库层就是原子操作。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "batch.compensation.stale-running-reconciler",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class StaleCompensationCommandReconciler {

  static final String ERROR_CODE = "STALE_RUNNING_TIMEOUT";

  private final CompensationCommandMapper compensationCommandMapper;

  @Value("${batch.compensation.stale-running-reconciler.timeout-seconds:3600}")
  private long timeoutSeconds;

  @Value("${batch.compensation.stale-running-reconciler.batch-size:100}")
  private int batchSize;

  @Scheduled(
      initialDelayString =
          "${batch.compensation.stale-running-reconciler.initial-delay-millis:60000}",
      fixedDelayString = "${batch.compensation.stale-running-reconciler.fixed-delay-millis:60000}")
  @SchedulerLock(
      name = "stale_compensation_reconcile",
      lockAtMostFor = "PT15M",
      lockAtLeastFor = "PT1M")
  public void reconcile() {
    if (timeoutSeconds <= 0 || batchSize <= 0) {
      return;
    }
    int updated =
        compensationCommandMapper.markStaleRunningFailed(
            CompensationCommandStatus.RUNNING.code(),
            CompensationCommandStatus.FAILED.code(),
            BatchDateTimeSupport.utcNow().minusSeconds(timeoutSeconds),
            ERROR_CODE,
            "compensation command stayed RUNNING beyond timeout; marked failed by reconciler",
            batchSize);
    if (updated > 0) {
      log.warn(
          "stale compensation RUNNING reconciled: updated={}, timeoutSeconds={}, batchSize={}",
          updated,
          timeoutSeconds,
          batchSize);
    }
  }
}
