package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.orchestrator.application.scheduler.WaitingCapacityReleasedEvent;
import io.github.pinpols.batch.orchestrator.application.service.governance.RetryGovernanceService;
import io.github.pinpols.batch.orchestrator.application.service.task.InstanceManagementResults.InstanceAction;
import io.github.pinpols.batch.orchestrator.application.service.task.InstanceManagementResults.PartitionAction;
import io.github.pinpols.batch.orchestrator.application.service.task.InstanceManagementResults.RetryFailedPartitions;
import io.github.pinpols.batch.orchestrator.domain.command.JobInstanceTerminalStatusCommand;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.param.FinishTaskParam;
import io.github.pinpols.batch.orchestrator.domain.param.MarkPartitionStatusParam;
import io.github.pinpols.batch.orchestrator.domain.query.JobPartitionQuery;
import io.github.pinpols.batch.orchestrator.domain.query.JobTaskQuery;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

  private static final Set<String> CANCELLABLE = Set.of(
      JobInstanceStatus.CREATED.code(),
      JobInstanceStatus.WAITING.code(),
      JobInstanceStatus.READY.code());
  private static final Set<String> TERMINABLE = Set.of(JobInstanceStatus.RUNNING.code());
  private static final Set<String> PARTITION_CANCELLABLE = Set.of(
      PartitionStatus.CREATED.code(), PartitionStatus.WAITING.code(), PartitionStatus.READY.code());
  private static final Set<String> TASK_CANCELLABLE =
      Set.of(TaskStatus.CREATED.code(), TaskStatus.READY.code());
  private static final String PARTITION_CANCELLED_ERROR_CODE = "PARTITION_CANCELLED";
  // ADR-044:仅 RUNNING 可暂停(停发新分区,在途自然终结);PAUSED 可恢复回 RUNNING。
  private static final Set<String> PAUSABLE = Set.of(JobInstanceStatus.RUNNING.code());
  private static final Set<String> RESUMABLE = Set.of(JobInstanceStatus.PAUSED.code());

  private final JobInstanceMapper jobInstanceMapper;
  private final JobPartitionMapper jobPartitionMapper;
  private final JobTaskMapper jobTaskMapper;
  private final JobInstanceTerminalStatusApplicationService
      jobInstanceTerminalStatusApplicationService;
  private final RetryGovernanceService retryGovernanceService;
  private final TaskOutcomeInstanceProgressor taskOutcomeInstanceProgressor;
  private final ApplicationEventPublisher applicationEventPublisher;

  public InstanceAction cancel(String tenantId, Long id) {
    JobInstanceEntity loadedInstance = jobInstanceMapper.selectById(tenantId, id);
    JobInstanceEntity instance = Guard.requireFound(loadedInstance, "job instance not found");
    if (JobInstanceStatus.RUNNING.code().equals(instance.getInstanceStatus())) {
      int requested = jobTaskMapper.requestCancelByInstance(tenantId, id);
      return new InstanceAction(id, instance.getInstanceNo(), "CANCEL_REQUESTED", requested);
    }
    return transition(instance, tenantId, id, CANCELLABLE, JobInstanceStatus.CANCELLED.code());
  }

  public InstanceAction terminate(String tenantId, Long id) {
    return transition(tenantId, id, TERMINABLE, JobInstanceStatus.TERMINATED.code());
  }

  /** ADR-044 暂停 RUNNING → PAUSED:停发新分区,在途自然终结,不破坏性 kill。 */
  public InstanceAction pause(String tenantId, Long id) {
    return lifecycleTransition(tenantId, id, PAUSABLE, JobInstanceStatus.PAUSED.code());
  }

  /** ADR-044 恢复 PAUSED → RUNNING:重新纳入派发,已成功分区不重跑(靠幂等)。 */
  public InstanceAction resume(String tenantId, Long id) {
    return lifecycleTransition(tenantId, id, RESUMABLE, JobInstanceStatus.RUNNING.code());
  }

  /**
   * 非终态生命周期 CAS 转换(pause/resume 专用)。
   *
   * <p>不走终态 reconcile、不动 finished_at,仅 allowedFrom + version 守护。
   */
  private InstanceAction lifecycleTransition(
      String tenantId, Long id, Set<String> allowedFrom, String targetStatus) {
    JobInstanceEntity loadedInstance = jobInstanceMapper.selectById(tenantId, id);
    JobInstanceEntity instance = Guard.requireFound(loadedInstance, "job instance not found");
    if (!allowedFrom.contains(instance.getInstanceStatus())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          ResultCode.STATE_CONFLICT.detailKey(),
          "cannot transition from " + instance.getInstanceStatus() + " to " + targetStatus);
    }
    int rows =
        jobInstanceMapper.updateLifecycleStatus(tenantId, id, targetStatus, instance.getVersion());
    if (rows == 0) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.common.concurrent_modification");
    }
    return InstanceAction.status(id, instance.getInstanceNo(), targetStatus);
  }

  @Transactional
  public PartitionAction cancelPartition(String tenantId, Long id) {
    JobPartitionEntity partition = findPartition(tenantId, id);
    jobInstanceMapper.acquireInstanceAdvisoryLock(tenantId, partition.getJobInstanceId());
    partition = findPartition(tenantId, id);
    if (!PARTITION_CANCELLABLE.contains(partition.getPartitionStatus())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          ResultCode.STATE_CONFLICT.detailKey(),
          "cannot cancel partition from " + partition.getPartitionStatus());
    }

    JobTaskQuery partitionTaskQuery =
        JobTaskQuery.ofPartition(tenantId, partition.getJobInstanceId(), id);
    List<JobTaskEntity> partitionTasks = jobTaskMapper.selectByQuery(partitionTaskQuery);
    List<JobTaskEntity> pendingTasks = partitionTasks.stream()
        .filter(task -> TASK_CANCELLABLE.contains(task.getTaskStatus()))
        .toList();
    if (EmptyChecks.isEmpty(pendingTasks)) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.common.concurrent_modification");
    }
    boolean hasRunningTask = partitionTasks.stream()
        .anyMatch(task -> TaskStatus.RUNNING.code().equals(task.getTaskStatus()));
    if (hasRunningTask) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.common.concurrent_modification");
    }

    Instant finishedAt = BatchDateTimeSupport.utcNow();
    JobTaskEntity progressTask = null;
    for (JobTaskEntity task : pendingTasks) {
      JobTaskEntity cancelledTask = jobTaskMapper.finishTask(FinishTaskParam.builder()
          .tenantId(tenantId)
          .id(task.getId())
          .taskStatus(TaskStatus.CANCELLED.code())
          .expectedStatus(task.getTaskStatus())
          .errorCode(PARTITION_CANCELLED_ERROR_CODE)
          .errorMessage("Cancelled before task execution by partition operation")
          .failureClass(null)
          .expectedVersion(task.getVersion())
          .build());
      if (EmptyChecks.isNull(cancelledTask)) {
        throw BizException.of(ResultCode.STATE_CONFLICT, "error.common.concurrent_modification");
      }
      if (EmptyChecks.isNull(progressTask)) {
        progressTask = cancelledTask;
      }
    }

    JobInstanceEntity progressedInstance =
        jobPartitionMapper.markTerminalStatusAndLoadInstance(MarkPartitionStatusParam.builder()
            .tenantId(tenantId)
            .id(id)
            .partitionStatus(PartitionStatus.CANCELLED.code())
            .runningStatus(PartitionStatus.RUNNING.code())
            .terminalStatus1(PartitionStatus.SUCCESS.code())
            .terminalStatus2(PartitionStatus.FAILED.code())
            .terminalStatus3(PartitionStatus.CANCELLED.code())
            .terminalStatus4(PartitionStatus.TERMINATED.code())
            .expectedVersion(partition.getVersion())
            .build());
    if (EmptyChecks.isNull(progressedInstance)) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.common.concurrent_modification");
    }

    TaskOutcomeCommand command = TaskOutcomeCommand.builder()
        .tenantId(tenantId)
        .taskId(progressTask.getId())
        .success(false)
        .errorCode(PARTITION_CANCELLED_ERROR_CODE)
        .errorMessage("Cancelled before task execution by partition operation")
        .build();
    taskOutcomeInstanceProgressor.advance(command, progressTask, progressedInstance, finishedAt);
    applicationEventPublisher.publishEvent(new WaitingCapacityReleasedEvent(tenantId));
    return new PartitionAction(id, PartitionStatus.CANCELLED.code());
  }

  public PartitionAction retryPartition(String tenantId, Long id) {
    JobPartitionEntity partition = findPartition(tenantId, id);
    if (!PartitionStatus.FAILED.code().equals(partition.getPartitionStatus())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          ResultCode.STATE_CONFLICT.detailKey(),
          "can only retry FAILED partitions, current: " + partition.getPartitionStatus());
    }
    String eventKey = manualRetryEventKey(tenantId, partition);
    retryGovernanceService.retryPartition(tenantId, id, eventKey);
    return new PartitionAction(id, PartitionStatus.READY.code());
  }

  public RetryFailedPartitions retryFailedPartitions(String tenantId, Long instanceId) {
    JobInstanceEntity instance = Guard.requireFound(
        jobInstanceMapper.selectById(tenantId, instanceId), "job instance not found");
    List<JobPartitionEntity> failedPartitions = jobPartitionMapper.selectByQuery(
        new JobPartitionQuery(tenantId, instanceId, PartitionStatus.FAILED.code(), null));
    if (EmptyChecks.isEmpty(failedPartitions)) {
      return new RetryFailedPartitions(instanceId, instance.getInstanceNo(), 0, 0, 0, List.of());
    }
    int retried = 0;
    int conflicts = 0;
    List<Long> accepted = new ArrayList<>();
    for (JobPartitionEntity partition : failedPartitions) {
      try {
        Long partitionId = partition.getId();
        String eventKey = manualRetryEventKey(tenantId, partition);
        retryGovernanceService.retryPartition(tenantId, partitionId, eventKey);
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
    JobPartitionEntity loadedPartition = jobPartitionMapper.selectById(tenantId, id);
    return Guard.requireFound(loadedPartition, "partition not found");
  }

  private String manualRetryEventKey(String tenantId, JobPartitionEntity partition) {
    Long partitionVersion = partition.getVersion();
    Long version = EmptyChecks.isNull(partitionVersion) ? 0L : partitionVersion;
    Long partitionId = partition.getId();
    return tenantId + ":manual-partition-retry:" + partitionId + ":" + version;
  }

  private InstanceAction transition(
      String tenantId, Long id, Set<String> allowedFrom, String targetStatus) {
    JobInstanceEntity loadedInstance = jobInstanceMapper.selectById(tenantId, id);
    JobInstanceEntity instance = Guard.requireFound(loadedInstance, "job instance not found");
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
          ResultCode.STATE_CONFLICT.detailKey(),
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
