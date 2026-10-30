package io.github.pinpols.batch.orchestrator.application.service.governance;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.RetryScheduleStatus;
import io.github.pinpols.batch.common.enums.RunMode;
import io.github.pinpols.batch.common.enums.StepInstanceStatus;
import io.github.pinpols.batch.common.enums.TaskErrorCodes;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.BatchMdc;
import io.github.pinpols.batch.common.logging.StructuredLogField;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import io.github.pinpols.batch.orchestrator.application.engine.command.TaskDispatchEventCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobStepInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.RetryScheduleEntity;
import io.github.pinpols.batch.orchestrator.domain.query.JobTaskQuery;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobStepInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.RetryScheduleMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 重试重排队协调器。
 *
 * <p>它只负责把分区或任务从可重试终态恢复到 READY，并在同一调用事务中写入 dispatch outbox。
 * 重试策略、死信生命周期和事务创建仍由 {@link DefaultRetryGovernanceService} 负责；本组件不声明事务，
 * 以便调度、人工重试和死信重放沿用各自调用方的事务边界。
 */
@Component
@RequiredArgsConstructor
public final class RetryRequeueCoordinator {

  private final RetryScheduleMapper retryScheduleMapper;
  private final JobTaskMapper jobTaskMapper;
  private final JobPartitionMapper jobPartitionMapper;
  private final JobInstanceMapper jobInstanceMapper;
  private final JobStepInstanceMapper jobStepInstanceMapper;
  private final TaskDispatchOutboxService taskDispatchOutboxService;
  private final WorkflowRunMapper workflowRunMapper;

  /** 单条 retry schedule 的 claim、重排队和成功收口必须在同一调用事务中完成。 */
  void requeueRetry(RetryScheduleEntity retrySchedule) {
    if (retryScheduleMapper.markRunning(
            retrySchedule.getTenantId(),
            retrySchedule.getId(),
            RetryScheduleStatus.WAITING.code(),
            RetryScheduleStatus.RUNNING.code())
        <= 0) {
      return;
    }
    requeuePartition(
        retrySchedule.getTenantId(),
        retrySchedule.getRelatedId(),
        retrySchedule.getTenantId() + ":retry:" + retrySchedule.getId());
    retryScheduleMapper.markSuccess(
        retrySchedule.getTenantId(),
        retrySchedule.getId(),
        RetryScheduleStatus.RUNNING.code(),
        RetryScheduleStatus.SUCCESS.code());
  }

  void requeuePartition(String tenantId, Long partitionId, String eventKey) {
    requeuePartition(tenantId, partitionId, eventKey, false);
  }

  void requeuePartitionAfterVerifiedUnknownOutcome(
      String tenantId, Long partitionId, String expectedSourceReference, String eventKey) {
    requeuePartition(tenantId, partitionId, eventKey, true, expectedSourceReference);
  }

  private void requeuePartition(
      String tenantId, Long partitionId, String eventKey, boolean allowVerifiedUnknownOutcome) {
    requeuePartition(tenantId, partitionId, eventKey, allowVerifiedUnknownOutcome, null);
  }

  private void requeuePartition(
      String tenantId,
      Long partitionId,
      String eventKey,
      boolean allowVerifiedUnknownOutcome,
      String expectedSourceReference) {
    JobPartitionEntity partition = jobPartitionMapper.selectById(tenantId, partitionId);
    if (partition == null) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.partition.retry_not_found");
    }
    JobInstanceEntity jobInstance =
        requireExecutableInstance(tenantId, partition.getJobInstanceId());
    String traceId = jobInstance.getTraceId();
    boolean injectMdc = traceId != null && !traceId.isBlank();
    if (injectMdc) {
      BatchMdc.put(StructuredLogField.TENANT_ID, tenantId);
      BatchMdc.put(StructuredLogField.TRACE_ID, traceId);
      BatchMdc.put(
          StructuredLogField.JOB_INSTANCE_ID,
          jobInstance.getId() == null ? null : String.valueOf(jobInstance.getId()));
    }
    try {
      JobTaskQuery partitionTaskQuery =
          JobTaskQuery.ofPartition(tenantId, jobInstance.getId(), partition.getId());
      List<JobTaskEntity> tasks = jobTaskMapper.selectByQuery(partitionTaskQuery);
      JobTaskEntity task = tasks.stream()
          .sorted((left, right) -> Integer.compare(
              left.getTaskSeq() == null ? 0 : left.getTaskSeq(),
              right.getTaskSeq() == null ? 0 : right.getTaskSeq()))
          .findFirst()
          .orElseThrow(() -> BizException.of(ResultCode.NOT_FOUND, "error.task.retry_not_found"));
      List<JobTaskEntity> unknownOutcomeTasks = tasks.stream()
          .filter(
              candidate -> TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN.equals(candidate.getErrorCode()))
          .toList();
      if (allowVerifiedUnknownOutcome
          && EmptyChecks.isEmpty(Objects.requireNonNull(unknownOutcomeTasks))) {
        throw BizException.of(
            ResultCode.STATE_CONFLICT,
            "error.dead_letter.external_outcome_resolution_not_applicable");
      }
      if (allowVerifiedUnknownOutcome) {
        if (unknownOutcomeTasks.size() != 1) {
          requireResolvedExternalOutcome(unknownOutcomeTasks);
        }
        task = unknownOutcomeTasks.getFirst();
        String actualSourceReference = jobInstance.getInstanceNo() + ":" + task.getId();
        if (!actualSourceReference.equals(expectedSourceReference)) {
          throw BizException.of(
              ResultCode.STATE_CONFLICT, "error.dead_letter.external_outcome_source_mismatch");
        }
        if (jobTaskMapper.markExternalOutcomeResolution(tenantId, task.getId(), task.getVersion())
            <= 0) {
          throw new TransientConflictException(
              "external outcome resolution already consumed or task version changed: taskId="
                  + task.getId());
        }
        task.setVersion(task.getVersion() + 1);
      } else if (!EmptyChecks.isEmpty(Objects.requireNonNull(unknownOutcomeTasks))) {
        requireResolvedExternalOutcome(unknownOutcomeTasks);
      }

      JobStepInstanceEntity stepInstance =
          jobStepInstanceMapper.selectByJobTaskId(tenantId, task.getId());
      if (stepInstance != null) {
        int nextRetryCount = Optional.ofNullable(stepInstance.getRetryCount()).orElse(0) + 1;
        jobStepInstanceMapper.resetForRetryByJobTaskId(
            tenantId, task.getId(), nextRetryCount, StepInstanceStatus.READY.code());
      }
      if (jobPartitionMapper.resetForDispatch(
              tenantId, partition.getId(), PartitionStatus.READY.code(), partition.getVersion())
          <= 0) {
        throw new TransientConflictException(
            "partition version conflict, requeue aborted: partitionId=" + partition.getId());
      }
      if (jobTaskMapper.resetForRetry(
              tenantId, task.getId(), TaskStatus.READY.code(), task.getVersion())
          <= 0) {
        throw new TransientConflictException(
            "task version conflict, requeue aborted: taskId=" + task.getId());
      }
      TaskDispatchEventCommand dispatchCommand = TaskDispatchEventCommand.builder()
          .jobInstance(jobInstance)
          .task(task)
          .partition(partition)
          .traceId(jobInstance.getTraceId())
          .eventKey(eventKey)
          .runModeOverride(RunMode.RETRY)
          .build();
      taskDispatchOutboxService.writeDispatchEvent(dispatchCommand);
    } finally {
      if (injectMdc) {
        BatchMdc.remove(StructuredLogField.JOB_INSTANCE_ID);
        BatchMdc.remove(StructuredLogField.TRACE_ID);
        BatchMdc.remove(StructuredLogField.TENANT_ID);
      }
    }
  }

  void requeueTaskWithoutPartition(String tenantId, JobTaskEntity task, String eventKey) {
    JobInstanceEntity jobInstance = requireExecutableInstance(tenantId, task.getJobInstanceId());
    requireResolvedExternalOutcome(List.of(task));
    JobStepInstanceEntity stepInstance =
        jobStepInstanceMapper.selectByJobTaskId(tenantId, task.getId());
    if (stepInstance != null) {
      int nextRetryCount = Optional.ofNullable(stepInstance.getRetryCount()).orElse(0) + 1;
      jobStepInstanceMapper.resetForRetryByJobTaskId(
          tenantId, task.getId(), nextRetryCount, StepInstanceStatus.READY.code());
    }
    if (jobTaskMapper.resetForRetry(
            tenantId, task.getId(), TaskStatus.READY.code(), task.getVersion())
        <= 0) {
      throw new TransientConflictException(
          "task version conflict, requeue aborted: taskId=" + task.getId());
    }
    TaskDispatchEventCommand dispatchCommand = TaskDispatchEventCommand.builder()
        .jobInstance(jobInstance)
        .task(task)
        .partition(null)
        .traceId(jobInstance.getTraceId())
        .eventKey(eventKey)
        .runModeOverride(RunMode.RETRY)
        .build();
    taskDispatchOutboxService.writeDispatchEvent(dispatchCommand);
  }

  private static void requireResolvedExternalOutcome(List<JobTaskEntity> tasks) {
    boolean hasUnknownOutcome = tasks.stream()
        .anyMatch(task -> TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN.equals(task.getErrorCode()));
    if (hasUnknownOutcome) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.task.retry_unknown_outcome_requires_resolution");
    }
  }

  private JobInstanceEntity requireExecutableInstance(String tenantId, Long instanceId) {
    // 与 REPORT 使用相同锁序；锁后重读，不用扫描时的父实例快照决定能否复位。
    jobInstanceMapper.acquireInstanceAdvisoryLock(tenantId, instanceId);
    JobInstanceEntity instance = jobInstanceMapper.selectById(tenantId, instanceId);
    if (EmptyChecks.isNull(instance)) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.partition.retry_instance_not_found");
    }
    if (EmptyChecks.isBlank(instance.getInstanceStatus())
        || !JobInstanceStatus.activeCodes().contains(instance.getInstanceStatus())) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.partition.retry_execution_closed");
    }
    WorkflowRunEntity workflow = workflowRunMapper.selectByNodeJobInstanceId(tenantId, instanceId);
    boolean workflowIsNotActive = EmptyChecks.isNotNull(workflow)
        && (EmptyChecks.isBlank(workflow.getRunStatus())
            || !WorkflowRunStatus.activeCodes().contains(workflow.getRunStatus()));
    if (workflowIsNotActive) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.partition.retry_execution_closed");
    }
    boolean retryTargetIsPaused =
        JobInstanceStatus.PAUSED.code().equals(instance.getInstanceStatus())
            || (EmptyChecks.isNotNull(workflow)
                && WorkflowRunStatus.PAUSED.code().equals(workflow.getRunStatus()));
    if (retryTargetIsPaused) {
      throw new ExecutionPausedException();
    }
    return instance;
  }

  /** 暂停是可恢复等待，不消耗重试预算，也不把到期工作记作失败。 */
  static final class ExecutionPausedException extends RuntimeException {}

  /** 乐观锁 CAS 失败时回滚当前事务，保留 retry schedule 等待下轮重新 claim。 */
  static final class TransientConflictException extends RuntimeException {

    TransientConflictException(String message) {
      super(message);
    }
  }
}
