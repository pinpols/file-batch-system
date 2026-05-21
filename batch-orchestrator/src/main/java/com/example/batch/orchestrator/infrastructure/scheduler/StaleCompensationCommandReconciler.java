package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.common.enums.CompensationCommandStatus;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.mapper.CompensationCommandMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconciles compensation commands left RUNNING after a JVM crash between command insert and
 * terminal status update.
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
  @Transactional
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
