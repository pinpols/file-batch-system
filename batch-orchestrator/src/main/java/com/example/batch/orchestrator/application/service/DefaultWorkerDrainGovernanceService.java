package com.example.batch.orchestrator.application.service;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.common.exception.BizException;
import com.example.batch.orchestrator.config.WorkerDrainProperties;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.WorkerRegistryRecord;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.repository.WorkerRegistryRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultWorkerDrainGovernanceService implements WorkerDrainGovernanceService {

    private final WorkerRegistryRepository workerRegistryRepository;
    private final JobTaskMapper jobTaskMapper;
    private final RetryGovernanceService retryGovernanceService;
    private final WorkerDrainProperties workerDrainProperties;

    @Override
    @Transactional
    public WorkerRegistryRecord startDrain(String tenantId, String workerCode, Integer timeoutSeconds) {
        validateTenant(tenantId);
        if (!StringUtils.hasText(workerCode)) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "workerCode is required");
        }
        WorkerRegistryRecord registry = requireRegistry(tenantId, workerCode);
        if (WorkerRegistryStatus.DECOMMISSIONED.code().equals(registry.getStatus())) {
            throw new BizException(ResultCode.STATE_CONFLICT, "worker is decommissioned");
        }
        int seconds = timeoutSeconds != null && timeoutSeconds > 0
                ? timeoutSeconds
                : workerDrainProperties.getDefaultTimeoutSeconds();
        Instant now = Instant.now();
        registry.setStatus(WorkerRegistryStatus.DRAINING.code());
        registry.setDrainStartedAt(now);
        registry.setDrainDeadlineAt(now.plusSeconds(seconds));
        registry.setHeartbeatAt(now);
        return workerRegistryRepository.save(registry);
    }

    @Override
    @Transactional
    public WorkerRegistryRecord forceOffline(String tenantId, String workerCode) {
        validateTenant(tenantId);
        requireRegistry(tenantId, workerCode);
        takeoverTasks(tenantId, workerCode);
        return markDecommissioned(tenantId, workerCode);
    }

    @Override
    @Transactional(readOnly = true)
    public List<JobTaskEntity> listClaimedTasks(String tenantId, String workerCode) {
        validateTenant(tenantId);
        if (!StringUtils.hasText(workerCode)) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "workerCode is required");
        }
        return jobTaskMapper.selectActiveByAssignedWorker(tenantId, workerCode);
    }

    @Override
    @Transactional
    public void takeoverAfterDrainTimeout(String tenantId, String workerCode) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(workerCode)) {
            return;
        }
        WorkerRegistryRecord registry = workerRegistryRepository.findFirstByTenantIdAndWorkerCode(tenantId, workerCode);
        if (registry == null || !WorkerRegistryStatus.DRAINING.code().equals(registry.getStatus())) {
            return;
        }
        log.warn("drain deadline exceeded, taking over tasks: tenantId={}, workerCode={}", tenantId, workerCode);
        takeoverTasks(tenantId, workerCode);
        markDecommissioned(tenantId, workerCode);
    }

    private void takeoverTasks(String tenantId, String workerCode) {
        List<JobTaskEntity> tasks = jobTaskMapper.selectActiveByAssignedWorker(tenantId, workerCode);
        for (JobTaskEntity task : tasks) {
            if (task == null || task.getId() == null) {
                continue;
            }
            try {
                retryGovernanceService.retryTask(
                        tenantId,
                        task.getId(),
                        tenantId + ":drain-takeover:" + task.getId()
                );
            } catch (RuntimeException ex) {
                log.warn("drain takeover failed for taskId={}: {}", task.getId(), ex.getMessage());
            }
        }
    }

    private WorkerRegistryRecord markDecommissioned(String tenantId, String workerCode) {
        WorkerRegistryRecord registry = requireRegistry(tenantId, workerCode);
        registry.setStatus(WorkerRegistryStatus.DECOMMISSIONED.code());
        registry.setDrainStartedAt(null);
        registry.setDrainDeadlineAt(null);
        registry.setHeartbeatAt(Instant.now());
        return workerRegistryRepository.save(registry);
    }

    private WorkerRegistryRecord requireRegistry(String tenantId, String workerCode) {
        WorkerRegistryRecord registry = workerRegistryRepository.findFirstByTenantIdAndWorkerCode(tenantId, workerCode);
        if (registry == null) {
            throw new BizException(ResultCode.NOT_FOUND, "worker not registered");
        }
        return registry;
    }

    private void validateTenant(String tenantId) {
        if (!StringUtils.hasText(tenantId)) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "tenantId is required");
        }
    }
}
