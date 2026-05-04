package com.example.batch.orchestrator.infrastructure.timeout;

import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.orchestrator.application.service.task.JobInstanceTerminalChildStateReconciler;
import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Job-level timeout 兜底强制器（ADR-参考 docs/analysis/orchestrator-vs-industry-2026-05-03.md §2.1）。
 *
 * <p>周期扫 {@code job_instance.instance_status='RUNNING' AND now - started_at >
 * job_definition.timeout_seconds} 的实例，CAS 推到 FAILED 终态。
 *
 * <p>与 {@code PartitionLeaseReclaimScheduler} 互补：lease reclaim 兜底 worker 心跳丢失（worker 宕机），timeout
 * enforcer 兜底业务跑得太久（worker 在但任务卡住）。CAS 失败安静跳过（并发已推进）。
 *
 * <p>{@code timeout_seconds = 0} 表示无 timeout（默认值），不会被本 enforcer 选中。
 */
@Slf4j
@Component
@ConditionalOnProperty(
    prefix = "batch.timeout",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@RequiredArgsConstructor
public class JobInstanceTimeoutEnforcer {

  private final JobInstanceMapper jobInstanceMapper;
  private final BatchOrchestratorGovernanceProperties governance;
  private final OrchestratorGracefulShutdown gracefulShutdown;
  private final MeterRegistry meterRegistry;
  private final JobInstanceTerminalChildStateReconciler terminalChildStateReconciler;
  private final AtomicBoolean running = new AtomicBoolean(false);

  @Scheduled(fixedDelayString = "${batch.timeout.poll-interval-millis:60000}")
  @SchedulerLock(
      name = "job_instance_timeout_enforce",
      lockAtMostFor = "PT2M",
      lockAtLeastFor = "PT10S")
  public void enforce() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    if (!running.compareAndSet(false, true)) {
      return;
    }
    try {
      int batchSize = Math.max(1, governance.timeout().getBatchSize());
      List<JobInstanceEntity> candidates = jobInstanceMapper.selectTimedOutCandidates(batchSize);
      if (candidates.isEmpty()) {
        return;
      }
      Instant now = Instant.now();
      int failed = 0;
      for (JobInstanceEntity ji : candidates) {
        int rows =
            jobInstanceMapper.updateStatus(
                ji.getTenantId(),
                ji.getId(),
                JobInstanceStatus.FAILED.code(),
                now,
                ji.getVersion());
        if (rows > 0) {
          failed++;
          terminalChildStateReconciler.reconcile(
              ji.getTenantId(), ji.getId(), JobInstanceStatus.FAILED.code());
          log.warn(
              "job_instance timeout enforced: id={} tenant={} jobCode={} startedAt={} (mark"
                  + " FAILED)",
              ji.getId(),
              ji.getTenantId(),
              ji.getJobCode(),
              ji.getStartedAt());
        } else {
          log.debug(
              "job_instance timeout CAS miss (concurrent advance): id={} tenant={}",
              ji.getId(),
              ji.getTenantId());
        }
      }
      if (failed > 0) {
        log.info("timeout enforcer tick: scanned={} marked_failed={}", candidates.size(), failed);
        meterRegistry.counter("batch.timeout.enforcer.failed.total").increment(failed);
      }
      if (candidates.size() >= batchSize) {
        log.warn(
            "timeout enforcer hit batch ceiling: scanned={} batchSize={} — consider raising"
                + " batch.timeout.batch-size or shortening interval",
            candidates.size(),
            batchSize);
      }
    } catch (RuntimeException ex) {
      log.warn("timeout enforcer tick failed: {}", ex.getMessage(), ex);
    } finally {
      running.set(false);
    }
  }
}
