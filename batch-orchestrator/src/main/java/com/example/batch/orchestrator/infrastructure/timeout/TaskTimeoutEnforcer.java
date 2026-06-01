package com.example.batch.orchestrator.infrastructure.timeout;

import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Task-level startToClose timeout 软取消器（SDK Phase 4 / ORCH-P4-2）。
 *
 * <p>周期扫 {@code job_task.task_status='RUNNING' AND now - started_at > task_timeout_seconds} 的
 * task，置 {@code cancel_requested=true}（复用 ORCH-P4-1 软取消）。SDK 下次 renew 收到 {@code
 * cancelRequested=true} 后主动停。
 *
 * <p>与 {@link JobInstanceTimeoutEnforcer} 的区别:job-instance enforcer 把整实例 CAS 推 FAILED（硬终止）；本器只对
 * 单个超时 task 发软取消信号，让 SDK 有机会优雅收尾 + 上报进度。{@code task_timeout_seconds} 派单期拷自 {@code
 * workflow_node.task_timeout_seconds}；NULL / 0 表示无 timeout，不会被选中。
 */
@Slf4j
@Component
@ConditionalOnProperty(
    prefix = "batch.timeout",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@RequiredArgsConstructor
public class TaskTimeoutEnforcer {

  private final JobTaskMapper jobTaskMapper;
  private final BatchOrchestratorGovernanceProperties governance;
  private final OrchestratorGracefulShutdown gracefulShutdown;
  private final MeterRegistry meterRegistry;
  private final AtomicBoolean running = new AtomicBoolean(false);

  @Scheduled(fixedDelayString = "${batch.timeout.poll-interval-millis:60000}")
  @SchedulerLock(name = "task_timeout_enforce", lockAtMostFor = "PT2M", lockAtLeastFor = "PT10S")
  public void enforce() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    if (!running.compareAndSet(false, true)) {
      return;
    }
    try {
      int batchSize = Math.max(1, governance.timeout().getBatchSize());
      List<JobTaskEntity> candidates = jobTaskMapper.selectTaskTimeoutCandidates(batchSize);
      if (candidates.isEmpty()) {
        return;
      }
      int cancelled = 0;
      for (JobTaskEntity task : candidates) {
        int rows = jobTaskMapper.requestCancel(task.getTenantId(), task.getId());
        if (rows > 0) {
          cancelled++;
          log.warn(
              "task startToClose timeout: tenant={} taskId={} startedAt={} timeoutSeconds={}"
                  + " (cancel requested)",
              task.getTenantId(),
              task.getId(),
              task.getStartedAt(),
              task.getTaskTimeoutSeconds());
        }
      }
      if (cancelled > 0) {
        log.info(
            "task timeout enforcer tick: scanned={} cancel_requested={}",
            candidates.size(),
            cancelled);
        meterRegistry.counter("batch.task.timeout.cancel.total").increment(cancelled);
      }
      if (candidates.size() >= batchSize) {
        log.warn(
            "task timeout enforcer hit batch ceiling: scanned={} batchSize={} — consider raising"
                + " batch.timeout.batch-size or shortening interval",
            candidates.size(),
            batchSize);
      }
    } catch (RuntimeException ex) {
      log.warn("task timeout enforcer tick failed: {}", ex.getMessage(), ex);
    } finally {
      running.set(false);
    }
  }
}
