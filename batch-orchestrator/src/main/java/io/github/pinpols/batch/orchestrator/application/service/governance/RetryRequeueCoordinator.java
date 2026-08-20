package io.github.pinpols.batch.orchestrator.application.service.governance;

import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.RetryScheduleStatus;
import io.github.pinpols.batch.common.enums.RunMode;
import io.github.pinpols.batch.common.enums.StepInstanceStatus;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.BatchMdc;
import io.github.pinpols.batch.common.logging.StructuredLogField;
import io.github.pinpols.batch.orchestrator.application.engine.TaskDispatchOutboxService;
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
import java.util.List;
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
    JobPartitionEntity partition = jobPartitionMapper.selectById(tenantId, partitionId);
    if (partition == null) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.partition.retry_not_found");
    }
    JobInstanceEntity jobInstance =
        jobInstanceMapper.selectById(tenantId, partition.getJobInstanceId());
    if (jobInstance == null) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.partition.retry_instance_not_found");
    }
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
      List<JobTaskEntity> tasks = jobTaskMapper.selectByQuery(
          new JobTaskQuery(tenantId, jobInstance.getId(), partition.getId(), null, null));
      JobTaskEntity task = tasks.stream()
          .sorted((left, right) -> Integer.compare(
              left.getTaskSeq() == null ? 0 : left.getTaskSeq(),
              right.getTaskSeq() == null ? 0 : right.getTaskSeq()))
          .findFirst()
          .orElseThrow(() -> BizException.of(ResultCode.NOT_FOUND, "error.task.retry_not_found"));

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
      taskDispatchOutboxService.writeDispatchEvent(
          jobInstance, task, partition, jobInstance.getTraceId(), eventKey, RunMode.RETRY);
    } finally {
      if (injectMdc) {
        BatchMdc.remove(StructuredLogField.JOB_INSTANCE_ID);
        BatchMdc.remove(StructuredLogField.TRACE_ID);
        BatchMdc.remove(StructuredLogField.TENANT_ID);
      }
    }
  }

  void requeueTaskWithoutPartition(String tenantId, JobTaskEntity task, String eventKey) {
    JobInstanceEntity jobInstance = jobInstanceMapper.selectById(tenantId, task.getJobInstanceId());
    if (jobInstance == null) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.partition.retry_instance_not_found");
    }
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
    taskDispatchOutboxService.writeDispatchEvent(
        jobInstance, task, null, jobInstance.getTraceId(), eventKey, RunMode.RETRY);
  }

  /** 乐观锁 CAS 失败时回滚当前事务，保留 retry schedule 等待下轮重新 claim。 */
  static final class TransientConflictException extends RuntimeException {

    TransientConflictException(String message) {
      super(message);
    }
  }
}
