package com.example.batch.worker.core.infrastructure;

import com.example.batch.worker.core.domain.WorkerRegistration;
import com.example.batch.worker.core.support.HeartbeatService;
import com.example.batch.worker.core.support.WorkerRegistryService;
import com.example.batch.worker.core.infrastructure.WorkerRuntimeState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultHeartbeatService implements HeartbeatService {

    private final WorkerRegistryService workerRegistryService;
    private final WorkerRuntimeState workerRuntimeState;

    @Override
    public void beat(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            return;
        }
        WorkerRegistration activeRegistration = workerRuntimeState.get(workerId);
        if (activeRegistration == null) {
            log.debug("skip heartbeat for unknown workerId={}", workerId);
            return;
        }
        if (activeRegistration.getCurrentLoad() == null) {
            activeRegistration.setCurrentLoad(0);
        }
        activeRegistration = workerRegistryService.renew(activeRegistration);
        workerRuntimeState.put(activeRegistration);
        log.debug("worker heartbeat: workerId={}", workerId);
    }
}
