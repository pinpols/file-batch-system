package com.example.batch.orchestrator.application.service;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.Guard;
import com.example.batch.orchestrator.config.WorkerDrainProperties;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.WorkerRegistryRecord;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.mapper.WorkerRegistryMapper;
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
  private final WorkerRegistryMapper workerRegistryMapper;
  private final JobTaskMapper jobTaskMapper;
  private final RetryGovernanceService retryGovernanceService;
  private final WorkerDrainProperties workerDrainProperties;

  @Override
  @Transactional
  public WorkerRegistryRecord startDrain(
      String tenantId, String workerCode, Integer timeoutSeconds) {
    validateTenant(tenantId);
    Guard.requireText(workerCode, "workerCode is required");
    WorkerRegistryRecord registry = requireRegistry(tenantId, workerCode);
    if (WorkerRegistryStatus.DECOMMISSIONED.code().equals(registry.status())) {
      throw new BizException(ResultCode.STATE_CONFLICT, "worker is decommissioned");
    }
    int seconds =
        timeoutSeconds != null && timeoutSeconds > 0
            ? timeoutSeconds
            : workerDrainProperties.getDefaultTimeoutSeconds();
    Instant now = Instant.now();
    registry =
        registry.withDrain(
            WorkerRegistryStatus.DRAINING.code(), now, now.plusSeconds(seconds), now);
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
  @Transactional
  public WorkerRegistryRecord takeover(String tenantId, String workerCode) {
    validateTenant(tenantId);
    WorkerRegistryRecord registry = requireRegistry(tenantId, workerCode);
    if (WorkerRegistryStatus.DECOMMISSIONED.code().equals(registry.status())) {
      throw new BizException(ResultCode.STATE_CONFLICT, "worker is decommissioned");
    }
    takeoverTasks(tenantId, workerCode);
    return markDecommissioned(tenantId, workerCode);
  }

  @Override
  @Transactional(readOnly = true)
  public List<JobTaskEntity> listClaimedTasks(String tenantId, String workerCode) {
    validateTenant(tenantId);
    Guard.requireText(workerCode, "workerCode is required");
    return jobTaskMapper.selectActiveByAssignedWorker(
        tenantId,
        workerCode,
        TaskStatus.RUNNING.code(),
        TaskStatus.READY.code(),
        TaskStatus.CREATED.code());
  }

  @Override
  @Transactional
  public void takeoverAfterDrainTimeout(String tenantId, String workerCode) {
    if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(workerCode)) {
      return;
    }
    WorkerRegistryRecord registry =
        workerRegistryRepository.findFirstByTenantIdAndWorkerCode(tenantId, workerCode);
    if (registry == null || !WorkerRegistryStatus.DRAINING.code().equals(registry.status())) {
      return;
    }
    log.warn(
        "drain deadline exceeded, taking over tasks: tenantId={}, workerCode={}",
        tenantId,
        workerCode);
    takeoverTasks(tenantId, workerCode);
    markDecommissioned(tenantId, workerCode);
  }

  private void takeoverTasks(String tenantId, String workerCode) {
    List<JobTaskEntity> tasks =
        jobTaskMapper.selectActiveByAssignedWorker(
            tenantId,
            workerCode,
            TaskStatus.RUNNING.code(),
            TaskStatus.READY.code(),
            TaskStatus.CREATED.code());
    for (JobTaskEntity task : tasks) {
      if (task == null || task.getId() == null) {
        continue;
      }
      try {
        retryGovernanceService.reclaimTask(
            tenantId, task.getId(), tenantId + ":drain-takeover:" + task.getId());
      } catch (RuntimeException ex) {
        log.warn("drain takeover failed for taskId={}: {}", task.getId(), ex.getMessage());
      }
    }
  }

  private WorkerRegistryRecord markDecommissioned(String tenantId, String workerCode) {
    requireRegistry(tenantId, workerCode);
    workerRegistryMapper.markDecommissioned(tenantId, workerCode, Instant.now());
    return workerRegistryRepository.findFirstByTenantIdAndWorkerCode(tenantId, workerCode);
  }

  private WorkerRegistryRecord requireRegistry(String tenantId, String workerCode) {
    return Guard.requireFound(
        workerRegistryRepository.findFirstByTenantIdAndWorkerCode(tenantId, workerCode),
        "worker not registered");
  }

  private void validateTenant(String tenantId) {
    Guard.requireText(tenantId, "tenantId is required");
  }
}
