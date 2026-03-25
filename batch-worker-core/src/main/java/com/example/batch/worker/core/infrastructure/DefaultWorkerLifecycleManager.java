package com.example.batch.worker.core.infrastructure;

import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.worker.core.domain.WorkerRegistration;
import com.example.batch.worker.core.support.WorkerSelfRegistrationService;
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
        log.info("worker registered: workerId={}, tenantId={}, workerType={}",
                activeRegistration.getWorkerId(), activeRegistration.getTenantId(), activeRegistration.getWorkerType());
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

        // Always mark as DRAINING first so orchestrator can stop dispatching new tasks immediately.
        workerRegistryService.updateStatus(activeRegistration, WorkerRegistryStatus.DRAINING.code());

        String finalStatus = hasActiveLeases(workerId)
                ? WorkerRegistryStatus.DRAINING.code()
                : WorkerRegistryStatus.DECOMMISSIONED.code();
        if (!WorkerRegistryStatus.DRAINING.code().equals(finalStatus)) {
            workerRegistryService.updateStatus(activeRegistration, finalStatus);
        }
        workerRuntimeState.remove(workerId);
        log.info("worker shutdown: workerId={}, status={}", workerId, finalStatus);
    }

    private boolean hasActiveLeases(String workerId) {
        return activeTaskLeaseRegistry.snapshot().stream()
                .anyMatch(lease -> workerId.equals(lease.getWorkerId()));
    }
}
