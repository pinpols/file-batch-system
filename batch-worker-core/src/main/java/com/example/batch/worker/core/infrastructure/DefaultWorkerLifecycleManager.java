package com.example.batch.worker.core.infrastructure;

import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.worker.core.domain.WorkerRegistration;
import com.example.batch.worker.core.support.WorkerLifecycleManager;
import com.example.batch.worker.core.support.WorkerSelfRegistrationService;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultWorkerLifecycleManager implements WorkerLifecycleManager {

  private final WorkerSelfRegistrationService workerRegistryService;
  private final WorkerRuntimeState workerRuntimeState;
  private final ActiveTaskLeaseRegistry activeTaskLeaseRegistry;

  @Override
  public WorkerRegistration start(WorkerRegistration registration) {
    WorkerRegistration activeRegistration = registration;
    if (activeRegistration.getWorkerId() == null || activeRegistration.getWorkerId().isBlank()) {
      activeRegistration.setWorkerId("worker-" + UUID.randomUUID());
    }
    activeRegistration.setStatus(WorkerRegistryStatus.ONLINE.code());
    activeRegistration.setActive(Boolean.TRUE);
    activeRegistration.setRegisteredAt(OffsetDateTime.now());
    activeRegistration.setLastHeartbeatAt(OffsetDateTime.now());
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
    try {
      workerRegistryService.updateStatus(activeRegistration, WorkerRegistryStatus.DRAINING.code());
      if (!WorkerRegistryStatus.DRAINING.code().equals(finalStatus)) {
        workerRegistryService.updateStatus(activeRegistration, finalStatus);
      }
    } catch (Exception exception) {
      log.warn(
          "worker shutdown status sync failed: workerId={}, targetStatus={}, cause={}",
          workerId,
          finalStatus,
          exception.getMessage());
    } finally {
      workerRuntimeState.remove(workerId);
    }
    log.info("worker shutdown handled: workerId={}, targetStatus={}", workerId, finalStatus);
  }

  private boolean hasActiveLeases(String workerId) {
    return activeTaskLeaseRegistry.snapshot().stream()
        .anyMatch(lease -> workerId.equals(lease.getWorkerId()));
  }
}
