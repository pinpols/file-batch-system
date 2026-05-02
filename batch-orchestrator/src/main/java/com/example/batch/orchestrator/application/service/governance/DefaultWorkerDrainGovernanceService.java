package com.example.batch.orchestrator.application.service.governance;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.application.engine.OutboxEventKeyGenerator;
import com.example.batch.orchestrator.config.WorkerDrainProperties;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.mapper.WorkerRegistryMapper;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Worker 下线治理：状态机 {@code ONLINE → DRAINING → DECOMMISSIONED}，提供两条下线路径。
 *
 * <ul>
 *   <li><b>优雅 drain</b>：{@link #startDrain} 把 worker 标记为 DRAINING 并打 drain deadline， worker
 *       不再接新任务、跑完手上任务后自然退出；{@link #takeoverAfterDrainTimeout} 定时器检查到 deadline 超期仍未 DECOMMISSIONED
 *       时强制接管剩余任务——防止卡死 worker 永远占着任务 lease。
 *   <li><b>强制下线</b>：{@link #forceOffline} / {@link #takeover} 立即调用 {@code
 *       retryGovernanceService.reclaimTask} 把 worker 手中的活跃任务（CREATED / READY / RUNNING）重排队，然后标
 *       DECOMMISSIONED。
 * </ul>
 *
 * <p>接管过程 {@code takeoverTasks} 对单条任务 reclaim 失败只 log.warn 继续下一条，不抛异常—— 单任务接管失败不能阻塞其他任务回收，失败任务会在下一轮
 * drain-timeout 或 reclaim 定时器捡回。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultWorkerDrainGovernanceService implements WorkerDrainGovernanceService {

  private final WorkerRegistryMapper workerRegistryMapper;
  private final JobTaskMapper jobTaskMapper;
  private final RetryGovernanceService retryGovernanceService;
  private final WorkerDrainProperties workerDrainProperties;

  @Override
  @Transactional
  public WorkerRegistryEntity startDrain(
      String tenantId, String workerCode, Integer timeoutSeconds) {
    validateTenant(tenantId);
    Guard.requireText(workerCode, "workerCode is required");
    WorkerRegistryEntity registry = requireRegistry(tenantId, workerCode);
    if (WorkerRegistryStatus.DECOMMISSIONED.code().equals(registry.status())) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.worker.decommissioned");
    }
    int seconds =
        timeoutSeconds != null && timeoutSeconds > 0
            ? timeoutSeconds
            : workerDrainProperties.getDefaultTimeoutSeconds();
    Instant now = Instant.now();
    registry =
        registry.withDrain(
            WorkerRegistryStatus.DRAINING.code(), now, now.plusSeconds(seconds), now);
    workerRegistryMapper.updateById(registry);
    return registry;
  }

  @Override
  @Transactional
  public WorkerRegistryEntity forceOffline(String tenantId, String workerCode) {
    validateTenant(tenantId);
    requireRegistry(tenantId, workerCode);
    takeoverTasks(tenantId, workerCode);
    return markDecommissioned(tenantId, workerCode);
  }

  @Override
  @Transactional
  public WorkerRegistryEntity takeover(String tenantId, String workerCode) {
    validateTenant(tenantId);
    WorkerRegistryEntity registry = requireRegistry(tenantId, workerCode);
    if (WorkerRegistryStatus.DECOMMISSIONED.code().equals(registry.status())) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.worker.decommissioned");
    }
    takeoverTasks(tenantId, workerCode);
    return markDecommissioned(tenantId, workerCode);
  }

  @Override
  @Transactional(readOnly = true)
  public List<JobTaskEntity> listClaimedTasks(String tenantId, String workerCode) {
    validateTenant(tenantId);
    Guard.requireText(workerCode, "workerCode is required");
    return jobTaskMapper.selectActiveByAssignedWorker(tenantId, workerCode);
  }

  @Override
  @Transactional
  public void takeoverAfterDrainTimeout(String tenantId, String workerCode) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(workerCode)) {
      return;
    }
    WorkerRegistryEntity registry =
        workerRegistryMapper.selectByTenantAndWorkerCode(tenantId, workerCode);
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
    List<JobTaskEntity> tasks = jobTaskMapper.selectActiveByAssignedWorker(tenantId, workerCode);
    for (JobTaskEntity task : tasks) {
      if (task == null || task.getId() == null) {
        continue;
      }
      try {
        retryGovernanceService.reclaimTask(
            tenantId,
            task.getId(),
            OutboxEventKeyGenerator.forReclaim(tenantId, task.getId(), workerCode));
      } catch (RuntimeException ex) {
        log.warn("drain takeover failed for taskId={}: {}", task.getId(), ex.getMessage());
      }
    }
  }

  private WorkerRegistryEntity markDecommissioned(String tenantId, String workerCode) {
    requireRegistry(tenantId, workerCode);
    workerRegistryMapper.markDecommissioned(tenantId, workerCode);
    return workerRegistryMapper.selectByTenantAndWorkerCode(tenantId, workerCode);
  }

  private WorkerRegistryEntity requireRegistry(String tenantId, String workerCode) {
    return Guard.requireFound(
        workerRegistryMapper.selectByTenantAndWorkerCode(tenantId, workerCode),
        "worker not registered");
  }

  private void validateTenant(String tenantId) {
    Guard.requireText(tenantId, "tenantId is required");
  }
}
