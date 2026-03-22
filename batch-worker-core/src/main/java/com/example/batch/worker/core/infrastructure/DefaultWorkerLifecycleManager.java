package com.example.batch.worker.core.infrastructure;

import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.worker.core.domain.WorkerRegistration;
import com.example.batch.worker.core.support.ActiveTaskLeaseRegistry;
import com.example.batch.worker.core.support.WorkerRegistryService;
import com.example.batch.worker.core.support.WorkerRuntimeState;
import com.example.batch.worker.core.support.WorkerLifecycleManager;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultWorkerLifecycleManager implements WorkerLifecycleManager {

    private final WorkerRegistryService workerRegistryService;
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
        log.info("worker registered: workerId={}, tenantId={}, workerType={}",
                activeRegistration.getWorkerId(), activeRegistration.getTenantId(), activeRegistration.getWorkerType());
        return activeRegistration;
    }

    @Override
    public void shutdown(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            return;
        }
        WorkerRegistration activeRegistration = workerRuntimeState.remove(workerId);
        if (activeRegistration != null) {
            activeRegistration.setActive(Boolean.FALSE);
            String targetStatus = hasActiveLeases(workerId)
                    ? WorkerRegistryStatus.DRAINING.code()
                    : WorkerRegistryStatus.DECOMMISSIONED.code();
            workerRegistryService.updateStatus(activeRegistration, targetStatus);
            log.info("worker shutdown: workerId={}, status={}", workerId, targetStatus);
        }
    }

    private boolean hasActiveLeases(String workerId) {
        return activeTaskLeaseRegistry.snapshot().stream()
                .anyMatch(lease -> workerId.equals(lease.getWorkerId()));
    }
}
