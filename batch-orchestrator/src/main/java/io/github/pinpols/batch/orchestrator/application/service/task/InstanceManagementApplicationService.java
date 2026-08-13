package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.orchestrator.application.service.governance.RetryGovernanceService;
import io.github.pinpols.batch.orchestrator.application.service.task.InstanceManagementResults.InstanceAction;
import io.github.pinpols.batch.orchestrator.application.service.task.InstanceManagementResults.PartitionAction;
import io.github.pinpols.batch.orchestrator.application.service.task.InstanceManagementResults.RetryFailedPartitions;
import io.github.pinpols.batch.orchestrator.domain.command.JobInstanceTerminalStatusCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.query.JobPartitionQuery;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 作业实例与分区的生命周期管理应用服务，提供取消、终止和重试等状态变更操作。
 *
 * <p>状态机约束通过常量集合（{@code CANCELLABLE}、{@code TERMINABLE}）声明， 所有状态转换均基于乐观锁版本号执行 CAS
 * 更新，若并发修改导致更新行数为零则抛出 {@link io.github.pinpols.batch.common.exception.BizException}，要求调用方重试。
 * 分区级操作（取消、重试）与实例级操作相互独立，允许细粒度的运维干预。
 */
@Service
@RequiredArgsConstructor
public class InstanceManagementApplicationService {

  private static final Set<String> CANCELLABLE = Set.of("CREATED", "WAITING", "READY");
  private static final Set<String> TERMINABLE = Set.of("RUNNING");
  private static final Set<String> PARTITION_CANCELLABLE = Set.of("CREATED", "WAITING", "READY");
  // ADR-044:仅 RUNNING 可暂停(停发新分区,在途自然终结);PAUSED 可恢复回 RUNNING。
  private static final Set<String> PAUSABLE = Set.of("RUNNING");
  private static final Set<String> RESUMABLE = Set.of("PAUSED");

  private final JobInstanceMapper jobInstanceMapper;
  private final JobPartitionMapper jobPartitionMapper;
  private final JobTaskMapper jobTaskMapper;
  private final JobInstanceTerminalStatusApplicationService
      jobInstanceTerminalStatusApplicationService;
  private final RetryGovernanceService retryGovernanceService;

  public InstanceAction cancel(String tenantId, Long id) {
    JobInstanceEntity instance =
        Guard.requireFound(jobInstanceMapper.selectById(tenantId, id), "job instance not found");
    if ("RUNNING".equals(instance.getInstanceStatus())) {
      int requested = jobTaskMapper.requestCancelByInstance(tenantId, id);
      return new InstanceAction(id, instance.getInstanceNo(), "CANCEL_REQUESTED", requested);
    }
    return transition(instance, tenantId, id, CANCELLABLE, "CANCELLED");
  }

  public InstanceAction terminate(String tenantId, Long id) {
    return transition(tenantId, id, TERMINABLE, "TERMINATED");
  }

  /** ADR-044 暂停 RUNNING → PAUSED:停发新分区,在途自然终结,不破坏性 kill。 */
  public InstanceAction pause(String tenantId, Long id) {
    return lifecycleTransition(tenantId, id, PAUSABLE, "PAUSED");
  }

  /** ADR-044 恢复 PAUSED → RUNNING:重新纳入派发,已成功分区不重跑(靠幂等)。 */
  public InstanceAction resume(String tenantId, Long id) {
    return lifecycleTransition(tenantId, id, RESUMABLE, "RUNNING");
  }

  /**
   * 非终态生命周期 CAS 转换(pause/resume 专用)。
   *
   * <p>不走终态 reconcile、不动 finished_at,仅 allowedFrom + version 守护。
   */
  private InstanceAction lifecycleTransition(
      String tenantId, Long id, Set<String> allowedFrom, String targetStatus) {
    JobInstanceEntity instance =
        Guard.requireFound(jobInstanceMapper.selectById(tenantId, id), "job instance not found");
    if (!allowedFrom.contains(instance.getInstanceStatus())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          "error.common.state_conflict_detail",
          "cannot transition from " + instance.getInstanceStatus() + " to " + targetStatus);
    }
    int rows =
        jobInstanceMapper.updateLifecycleStatus(tenantId, id, targetStatus, instance.getVersion());
    if (rows == 0) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.common.concurrent_modification");
    }
    return InstanceAction.status(id, instance.getInstanceNo(), targetStatus);
  }

  public PartitionAction cancelPartition(String tenantId, Long id) {
    JobPartitionEntity partition = findPartition(tenantId, id);
    if (!PARTITION_CANCELLABLE.contains(partition.getPartitionStatus())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          "error.common.state_conflict_detail",
          "cannot cancel partition from " + partition.getPartitionStatus());
    }
    int rows = jobPartitionMapper.promoteStatus(
        tenantId, id, partition.getPartitionStatus(), "CANCELLED", partition.getVersion());
    if (rows == 0) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.common.concurrent_modification");
    }
    return new PartitionAction(id, "CANCELLED");
  }

  public PartitionAction retryPartition(String tenantId, Long id) {
    JobPartitionEntity partition = findPartition(tenantId, id);
    if (!"FAILED".equals(partition.getPartitionStatus())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          "error.common.state_conflict_detail",
          "can only retry FAILED partitions, current: " + partition.getPartitionStatus());
    }
    retryGovernanceService.retryPartition(tenantId, id, manualRetryEventKey(tenantId, partition));
    return new PartitionAction(id, "READY");
  }

  public RetryFailedPartitions retryFailedPartitions(String tenantId, Long instanceId) {
    JobInstanceEntity instance = Guard.requireFound(
        jobInstanceMapper.selectById(tenantId, instanceId), "job instance not found");
    List<JobPartitionEntity> failedPartitions = jobPartitionMapper.selectByQuery(
        new JobPartitionQuery(tenantId, instanceId, "FAILED", null));
    if (EmptyChecks.isEmpty(failedPartitions)) {
      return new RetryFailedPartitions(instanceId, instance.getInstanceNo(), 0, 0, 0, List.of());
    }
    int retried = 0;
    int conflicts = 0;
    List<Long> accepted = new ArrayList<>();
    for (JobPartitionEntity partition : failedPartitions) {
      try {
        retryGovernanceService.retryPartition(
            tenantId, partition.getId(), manualRetryEventKey(tenantId, partition));
        retried++;
        accepted.add(partition.getId());
      } catch (RuntimeException retryFailure) {
        conflicts++;
      }
    }
    return new RetryFailedPartitions(
        instanceId,
        instance.getInstanceNo(),
        failedPartitions.size(),
        retried,
        conflicts,
        accepted);
  }

  private JobPartitionEntity findPartition(String tenantId, Long id) {
    return Guard.requireFound(jobPartitionMapper.selectById(tenantId, id), "partition not found");
  }

  private String manualRetryEventKey(String tenantId, JobPartitionEntity partition) {
    Long version = EmptyChecks.isNull(partition.getVersion()) ? 0L : partition.getVersion();
    return tenantId + ":manual-partition-retry:" + partition.getId() + ":" + version;
  }

  private InstanceAction transition(
      String tenantId, Long id, Set<String> allowedFrom, String targetStatus) {
    JobInstanceEntity instance =
        Guard.requireFound(jobInstanceMapper.selectById(tenantId, id), "job instance not found");
    return transition(instance, tenantId, id, allowedFrom, targetStatus);
  }

  private InstanceAction transition(
      JobInstanceEntity instance,
      String tenantId,
      Long id,
      Set<String> allowedFrom,
      String targetStatus) {
    if (!allowedFrom.contains(instance.getInstanceStatus())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          "error.common.state_conflict_detail",
          "cannot transition from " + instance.getInstanceStatus() + " to " + targetStatus);
    }
    JobInstanceTerminalStatusCommand cmd = new JobInstanceTerminalStatusCommand(
        tenantId, id, targetStatus, BatchDateTimeSupport.utcNow(), instance.getVersion());
    int rows =
        jobInstanceTerminalStatusApplicationService.updateTerminalStatusAndReconcileChildren(cmd);
    if (rows == 0) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.common.concurrent_modification");
    }
    return InstanceAction.status(id, instance.getInstanceNo(), targetStatus);
  }
}
