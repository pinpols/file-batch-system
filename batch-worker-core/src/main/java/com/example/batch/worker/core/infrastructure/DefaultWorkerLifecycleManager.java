package com.example.batch.worker.core.infrastructure;

import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.worker.core.domain.WorkerRegistration;
import com.example.batch.worker.core.support.WorkerLifecycleManager;
import com.example.batch.worker.core.support.WorkerSelfRegistrationService;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Worker 生命周期管理器默认实现：封装注册、心跳、状态迁移和关机流程。
 *
 * <p><b>关机顺序</b>：先将 worker 状态更新为 {@code DRAINING}（通知 Orchestrator 停止派发新任务）， 若此时 {@link
 * ActiveTaskLeaseRegistry} 中无 in-flight 任务则直接迁移到 {@code DECOMMISSIONED}； 否则保留 DRAINING 状态，由 {@link
 * GracefulKafkaShutdown} 负责等待排空后调 deactivate。
 *
 * <p>状态流：{@code ONLINE → DRAINING → DECOMMISSIONED}
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultWorkerLifecycleManager implements WorkerLifecycleManager {

  private final WorkerSelfRegistrationService workerRegistryService;
  private final WorkerRuntimeState workerRuntimeState;
  private final ActiveTaskLeaseRegistry activeTaskLeaseRegistry;
  private final BatchDateTimeSupport dateTimeSupport;

  @Override
  public WorkerRegistration start(WorkerRegistration registration) {
    WorkerRegistration activeRegistration = registration;
    if (activeRegistration.getWorkerId() == null || activeRegistration.getWorkerId().isBlank()) {
      activeRegistration.setWorkerId("worker-" + UUID.randomUUID());
    }
    activeRegistration.setStatus(WorkerRegistryStatus.ONLINE.code());
    activeRegistration.setActive(Boolean.TRUE);
    OffsetDateTime now = dateTimeSupport.nowOffsetUtc();
    activeRegistration.setRegisteredAt(now);
    activeRegistration.setLastHeartbeatAt(now);
    activeRegistration = workerRegistryService.register(activeRegistration);
    workerRuntimeState.put(activeRegistration);
    log.info(
        "worker registered: workerId={}, tenantId={}, workerType={}",
        activeRegistration.getWorkerId(),
        activeRegistration.getTenantId(),
        activeRegistration.getWorkerType());
    return activeRegistration;
  }

  @Override
  public void shutdown(String workerId) {
    if (workerId == null || workerId.isBlank()) {
      return;
    }
    WorkerRegistration activeRegistration = workerRuntimeState.get(workerId);
    if (activeRegistration == null) {
      return;
    }
    activeRegistration.setActive(Boolean.FALSE);

    // 先标记为 DRAINING，让 Orchestrator 立即停止分发新任务。
    String finalStatus =
        hasActiveLeases(workerId)
            ? WorkerRegistryStatus.DRAINING.code()
            : WorkerRegistryStatus.DECOMMISSIONED.code();
    // R2-P1-7：之前 catch 仅 warn + e.getMessage()，registry 留 ONLINE → orchestrator 继续派任务给死 worker。
    // 改为：最多 3 次指数退避重试，全部失败 ERROR + 完整 stack。
    // finally 仍清本地 map（本地状态已不准确，留着无意义）；DB 状态由 PartitionLeaseReclaimScheduler 回退。
    try {
      updateStatusWithRetry(activeRegistration, WorkerRegistryStatus.DRAINING.code(), workerId);
      if (!WorkerRegistryStatus.DRAINING.code().equals(finalStatus)) {
        updateStatusWithRetry(activeRegistration, finalStatus, workerId);
      }
    } finally {
      workerRuntimeState.remove(workerId);
    }
    log.info("worker shutdown handled: workerId={}, targetStatus={}", workerId, finalStatus);
  }

  private void updateStatusWithRetry(
      WorkerRegistration registration, String targetStatus, String workerId) {
    Exception lastError = null;
    for (int attempt = 1; attempt <= 3; attempt++) {
      try {
        workerRegistryService.updateStatus(registration, targetStatus);
        return;
      } catch (Exception ex) {
        lastError = ex;
        log.warn(
            "worker shutdown status sync attempt {}/3 failed: workerId={}, targetStatus={},"
                + " cause={}",
            attempt,
            workerId,
            targetStatus,
            ex.getMessage());
        if (attempt < 3) {
          try {
            Thread.sleep(200L * attempt); // 200ms / 400ms 退避
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
          }
        }
      }
    }
    log.error(
        "worker shutdown status sync FAILED after 3 retries — registry stays stale,"
            + " PartitionLeaseReclaimScheduler will reconcile: workerId={}, targetStatus={}",
        workerId,
        targetStatus,
        lastError);
  }

  private boolean hasActiveLeases(String workerId) {
    return activeTaskLeaseRegistry.snapshot().stream()
        .anyMatch(lease -> workerId.equals(lease.getWorkerId()));
  }
}
