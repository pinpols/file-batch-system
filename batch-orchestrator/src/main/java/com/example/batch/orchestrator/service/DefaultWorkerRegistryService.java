package com.example.batch.orchestrator.service;

import com.example.batch.common.dto.WorkerHeartbeatDto;
import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.orchestrator.domain.entity.WorkerRegistryRecord;
import com.example.batch.orchestrator.repository.WorkerRegistryRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DefaultWorkerRegistryService implements WorkerRegistryService {

    private final WorkerRegistryRepository workerRegistryRepository;

    @Override
    @Transactional
    public WorkerRegistryRecord register(WorkerHeartbeatDto request) {
        WorkerRegistryRecord registry = workerRegistryRepository.findFirstByTenantIdAndWorkerCode(request.tenantId(), request.workerCode());
        if (registry == null) {
            registry = new WorkerRegistryRecord();
            registry.setTenantId(request.tenantId());
            registry.setWorkerCode(request.workerCode());
        }
        registry.setWorkerGroup(request.workerGroup());
        registry.setStatus(resolveIncomingStatus(request, WorkerRegistryStatus.ONLINE.code(), registry.getStatus()));
        registry.setHeartbeatAt(firstHeartbeat(request));
        return workerRegistryRepository.save(registry);
    }

    @Override
    @Transactional
    public WorkerRegistryRecord heartbeat(String workerCode, WorkerHeartbeatDto request) {
        if (request == null) {
            return null;
        }
        WorkerRegistryRecord registry = workerRegistryRepository.findFirstByTenantIdAndWorkerCode(request.tenantId(), workerCode);
        if (registry == null) {
            return register(request);
        }
        registry.setStatus(resolveHeartbeatStatus(request, registry.getStatus()));
        registry.setHeartbeatAt(firstHeartbeat(request));
        return workerRegistryRepository.save(registry);
    }

    @Override
    @Transactional
    public void deactivate(String tenantId, String workerCode) {
        updateStatus(tenantId, workerCode, WorkerRegistryStatus.OFFLINE.code());
    }

    @Override
    @Transactional
    public WorkerRegistryRecord updateStatus(String tenantId, String workerCode, String status) {
        WorkerRegistryRecord registry = workerRegistryRepository.findFirstByTenantIdAndWorkerCode(tenantId, workerCode);
        if (registry == null) {
            return null;
        }
        registry.setStatus(resolveIncomingStatus(null, status, registry.getStatus()));
        registry.setHeartbeatAt(Instant.now());
        return workerRegistryRepository.save(registry);
    }

    private Instant firstHeartbeat(WorkerHeartbeatDto request) {
        return request.heartbeatAt() == null ? Instant.now() : request.heartbeatAt();
    }

    private String resolveHeartbeatStatus(WorkerHeartbeatDto request, String currentStatus) {
        if (WorkerRegistryStatus.DECOMMISSIONED.code().equals(currentStatus)) {
            return currentStatus;
        }
        if (WorkerRegistryStatus.DRAINING.code().equals(currentStatus)) {
            return currentStatus;
        }
        return resolveIncomingStatus(request, WorkerRegistryStatus.ONLINE.code(), currentStatus);
    }

    private String resolveIncomingStatus(WorkerHeartbeatDto request, String defaultStatus, String currentStatus) {
        String requestedStatus = request == null ? null : request.status();
        if (requestedStatus == null || requestedStatus.isBlank()) {
            return defaultStatus == null || defaultStatus.isBlank() ? currentStatus : defaultStatus;
        }
        return requestedStatus;
    }
}
