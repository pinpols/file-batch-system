package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.common.rls.RlsTenantContextHolder;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.application.service.governance.WorkerDrainGovernanceService;
import com.example.batch.orchestrator.config.WorkerDrainProperties;
import com.example.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.mapper.WorkerRegistryMapper;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker 排水（Drain）超时治理调度器。
 *
 * <p>默认每 15 秒轮询一次处于 {@code DRAINING} 状态的 Worker 注册记录， 若当前时刻已超过其 {@code drainDeadlineAt}，则调用 {@link
 * WorkerDrainGovernanceService} 执行超时接管逻辑（强制下线并重新派发该 Worker 持有的任务分区）。 ShedLock 锁名 {@code
 * worker_drain_timeout}，最长持锁 2 分钟，最短持锁 10 秒。 当 {@code batch.worker.drain.enabled=false} 或
 * Orchestrator 处于优雅停机时跳过执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkerDrainTimeoutScheduler {

  private final WorkerRegistryMapper workerRegistryMapper;
  private final WorkerDrainGovernanceService workerDrainGovernanceService;
  private final WorkerDrainProperties workerDrainProperties;
  private final OrchestratorGracefulShutdown gracefulShutdown;

  @Scheduled(fixedDelayString = "${batch.worker.drain.check-interval-millis:15000}")
  @SchedulerLock(name = "worker_drain_timeout", lockAtMostFor = "PT2M", lockAtLeastFor = "PT10S")
  public void expireDrains() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    if (!workerDrainProperties.isEnabled()) {
      return;
    }
    Instant now = BatchDateTimeSupport.utcNow();
    List<WorkerRegistryEntity> draining =
        workerRegistryMapper.selectByStatus(WorkerRegistryStatus.DRAINING.code());
    for (WorkerRegistryEntity worker : draining) {
      if (worker == null || worker.drainDeadlineAt() == null) {
        continue;
      }
      String tenantId = worker.tenantId();
      if (tenantId == null || tenantId.isBlank()) {
        continue;
      }
      if (!worker.drainDeadlineAt().isAfter(now)) {
        // RLS Phase B：takeoverAfterDrainTimeout 内部强制下线 worker + 重派 partition + 写审计日志，
        // 全链路 mapper 需要绑定 worker 所属 tenant。
        RlsTenantContextHolder.runWithTenant(
            tenantId,
            () ->
                workerDrainGovernanceService.takeoverAfterDrainTimeout(
                    worker.tenantId(), worker.workerCode()));
      }
    }
  }
}
