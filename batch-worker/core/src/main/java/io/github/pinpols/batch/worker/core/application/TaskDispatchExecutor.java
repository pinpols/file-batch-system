package io.github.pinpols.batch.worker.core.application;

import io.github.pinpols.batch.common.dto.EffectiveTaskConfig;
import io.github.pinpols.batch.common.kafka.TaskDispatchMessage;
import io.github.pinpols.batch.common.logging.BatchMdc;
import io.github.pinpols.batch.common.logging.StructuredLogField;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.worker.core.domain.PulledTask;
import io.github.pinpols.batch.worker.core.domain.WorkerExecutionResult;
import io.github.pinpols.batch.worker.core.support.TaskClaimItem;
import io.github.pinpols.batch.worker.core.support.TaskClaimResult;
import io.github.pinpols.batch.worker.core.support.TaskExecutionWrapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Kafka 消费层与 Worker 执行层之间的粘合：先向 Orchestrator CLAIM 任务所有权， 成功后再执行；CLAIM 失败（如已被其他 worker 抢占）则直接返回
 * null，消费层据此跳过。
 *
 * <p>保证 Orchestrator 是唯一状态主机：Kafka 仅负责任务路由，Worker 不能绕过 CLAIM 直接执行。
 *
 * <p>P1-2.2 起 message v2 已瘦身,业务字段(payload/businessKey/taskSeq/highWaterMarkIn 等) 全部从 CLAIM 返回的
 * {@link EffectiveTaskConfig} 读取,本类不再 fallback 到 message。task key +
 * 路由元数据(tenantId/taskId/instanceId 等)继续从 message 读。
 */
@Service
@RequiredArgsConstructor
public class TaskDispatchExecutor {

  private final TaskExecutionWrapper taskExecutionWrapper;

  /** Kafka 只负责把任务送到 worker，实际执行前仍然要回 Orchestrator 做 CLAIM。 */
  public WorkerExecutionResult execute(TaskDispatchMessage message, String workerId) {
    boolean executionRequestIsIncomplete = EmptyChecks.isNull(message)
        || EmptyChecks.isNull(message.taskId())
        || EmptyChecks.isBlank(workerId);
    if (executionRequestIsIncomplete) {
      return null;
    }
    Optional<EffectiveTaskConfig> claimed =
        taskExecutionWrapper.claim(message.tenantId(), message.taskId(), workerId);
    if (claimed.isEmpty()) {
      return null;
    }
    PulledTask task = buildTask(message, workerId, claimed.get());
    return taskExecutionWrapper.execute(task);
  }

  /**
   * ADR-046 P2 切片 2.3b:批量执行 —— 一次 {@code claim-batch} 认领 K 个独立 task(CLAIM 往返 O(N)→O(N/K)),
   * 对领到的任务在批内串行、逐项独立执行，沿用 {@link #execute} 的 per-task 执行与上报交接语义，返回逐项结果。
   *
   * <p>没领到的项跳过，与单条 {@link #execute} 返回 null 一致。REPORT 可按独立开关合并跨并发 listener 的完成项，
   * 每项仍同步等待确认或原 Outbox 交接；不等待整个 poll 执行结束。没有并发完成项时 HTTP 微批可只有一项。
   * 仅供批量 listener 调用；认领开关关闭时没有该调用方。
   */
  public List<WorkerExecutionResult> executeBatch(
      List<TaskDispatchMessage> messages, String workerId) {
    List<BatchItemExecution> detailed = executeBatchDetailed(messages, workerId);
    List<WorkerExecutionResult> results = new ArrayList<>();
    for (BatchItemExecution item : detailed) {
      if (item.error() != null) {
        if (item.error() instanceof RuntimeException runtimeException) {
          throw runtimeException;
        }
        throw new IllegalStateException(item.error());
      }
      if (item.result() != null) {
        results.add(item.result());
      }
    }
    return results;
  }

  /** 批量执行的逐项结果版本。Kafka 批量 consumer 需要知道是哪条消息失败，才能只 DLQ 毒丸消息，而不是把同 poll 的正常任务一起送进 DLQ。 */
  public List<BatchItemExecution> executeBatchDetailed(
      List<TaskDispatchMessage> messages, String workerId) {
    boolean batchExecutionRequestIsIncomplete =
        EmptyChecks.isEmpty(messages) || EmptyChecks.isBlank(workerId);
    if (batchExecutionRequestIsIncomplete) {
      return List.of();
    }
    Map<Long, TaskClaimItem> uniqueItems = new LinkedHashMap<>();
    for (TaskDispatchMessage m : messages) {
      if (m != null && m.taskId() != null) {
        Long deduplicationTaskId = m.taskId();
        TaskClaimItem claimItem = new TaskClaimItem(m.tenantId(), m.taskId(), workerId);
        uniqueItems.putIfAbsent(deduplicationTaskId, claimItem);
      }
    }
    List<TaskClaimItem> items = new ArrayList<>(uniqueItems.values());
    if (items.isEmpty()) {
      return List.of();
    }
    Map<Long, TaskClaimResult> claimedById = new LinkedHashMap<>();
    for (TaskClaimResult r : taskExecutionWrapper.claimBatch(items)) {
      if (r != null && r.taskId() != null) {
        claimedById.put(r.taskId(), r);
      }
    }
    Map<Long, PulledTask> claimedTasksById = new LinkedHashMap<>();
    for (TaskDispatchMessage message : messages) {
      boolean dispatchMessageIsMissingOrAlreadyClaimed = EmptyChecks.isNull(message)
          || EmptyChecks.isNull(message.taskId())
          || claimedTasksById.containsKey(message.taskId());
      if (dispatchMessageIsMissingOrAlreadyClaimed) {
        continue;
      }
      TaskClaimResult claim = claimedById.get(message.taskId());
      if (isClaimReadyForExecution(claim)) {
        Long taskId = message.taskId();
        PulledTask claimedTask = buildTask(message, workerId, claim.config());
        claimedTasksById.put(taskId, claimedTask);
      }
    }
    // CLAIM 一次取得整批 lease；在开始第一个任务前就登记每个成功项，令周期续租覆盖排队项。
    List<PulledTask> claimedBatch = new ArrayList<>(claimedTasksById.values());
    taskExecutionWrapper.registerClaimedBatch(claimedBatch);
    List<BatchItemExecution> detailedResults = new ArrayList<>();
    Set<Long> executedTaskIds = new HashSet<>();
    try {
      for (int messageIndex = 0; messageIndex < messages.size(); messageIndex++) {
        TaskDispatchMessage message = messages.get(messageIndex);
        if (EmptyChecks.isNull(message) || EmptyChecks.isNull(message.taskId())) {
          continue;
        }
        if (!executedTaskIds.add(message.taskId())) {
          detailedResults.add(BatchItemExecution.skipped(messageIndex, message));
          continue;
        }
        TaskClaimResult claim = claimedById.get(message.taskId());
        if (!isClaimReadyForExecution(claim)) {
          detailedResults.add(BatchItemExecution.skipped(messageIndex, message));
          continue; // 没领到 → 跳过(与单条 execute 返 null 一致)
        }
        putExecutionMdc(message, workerId);
        try {
          PulledTask claimedTask = claimedTasksById.get(message.taskId());
          WorkerExecutionResult result = taskExecutionWrapper.execute(claimedTask);
          detailedResults.add(BatchItemExecution.completed(messageIndex, message, result));
        } catch (Exception ex) {
          detailedResults.add(BatchItemExecution.failed(messageIndex, message, ex));
        } finally {
          BatchMdc.removeAll(
              StructuredLogField.TENANT_ID,
              StructuredLogField.TRACE_ID,
              StructuredLogField.TASK_ID,
              StructuredLogField.JOB_INSTANCE_ID,
              StructuredLogField.WORKFLOW_RUN_ID,
              StructuredLogField.PARTITION_ID,
              StructuredLogField.BATCH_DAY,
              StructuredLogField.ATTEMPT,
              StructuredLogField.WORKER_TYPE,
              StructuredLogField.WORKER_ID,
              StructuredLogField.RUN_MODE);
        }
      }
    } finally {
      // 致命错误继续向上传播；尚未开始的项交由租约超时恢复，不能在本进程永久续租。
      List<PulledTask> unexecutedClaims = claimedBatch.stream()
          .filter(task -> !executedTaskIds.contains(Long.valueOf(task.getTaskId())))
          .toList();
      taskExecutionWrapper.releaseUnexecutedClaims(unexecutedClaims);
    }
    return detailedResults;
  }

  public record BatchItemExecution(
      int messageIndex,
      TaskDispatchMessage message,
      WorkerExecutionResult result,
      Throwable error,
      boolean skipped) {

    public static BatchItemExecution completed(
        TaskDispatchMessage message, WorkerExecutionResult result) {
      return completed(-1, message, result);
    }

    public static BatchItemExecution completed(
        int messageIndex, TaskDispatchMessage message, WorkerExecutionResult result) {
      return new BatchItemExecution(messageIndex, message, result, null, false);
    }

    public static BatchItemExecution failed(TaskDispatchMessage message, Throwable error) {
      return failed(-1, message, error);
    }

    public static BatchItemExecution failed(
        int messageIndex, TaskDispatchMessage message, Throwable error) {
      return new BatchItemExecution(messageIndex, message, null, error, false);
    }

    public static BatchItemExecution skipped(TaskDispatchMessage message) {
      return skipped(-1, message);
    }

    public static BatchItemExecution skipped(int messageIndex, TaskDispatchMessage message) {
      return new BatchItemExecution(messageIndex, message, null, null, true);
    }
  }

  private static boolean isClaimReadyForExecution(TaskClaimResult claim) {
    return EmptyChecks.isNotNull(claim) && claim.claimed() && EmptyChecks.isNotNull(claim.config());
  }

  private static void putExecutionMdc(TaskDispatchMessage message, String workerId) {
    BatchMdc.put(StructuredLogField.TENANT_ID, message.tenantId());
    BatchMdc.put(StructuredLogField.TRACE_ID, message.traceId());
    Object rawTaskId = message.taskId();
    String mdcTaskId = EmptyChecks.isNull(rawTaskId) ? null : String.valueOf(message.taskId());
    BatchMdc.put(StructuredLogField.TASK_ID, mdcTaskId);
    Object rawJobInstanceId = message.jobInstanceId();
    String mdcJobInstanceId =
        EmptyChecks.isNull(rawJobInstanceId) ? null : String.valueOf(message.jobInstanceId());
    BatchMdc.put(StructuredLogField.JOB_INSTANCE_ID, mdcJobInstanceId);
    Object rawPartitionId = message.jobPartitionId();
    String mdcPartitionId =
        EmptyChecks.isNull(rawPartitionId) ? null : String.valueOf(message.jobPartitionId());
    BatchMdc.put(StructuredLogField.PARTITION_ID, mdcPartitionId);
    if (EmptyChecks.isNotNull(message.schedulingContext())) {
      Object rawWorkflowRunId = message.schedulingContext().workflowRunId();
      String mdcWorkflowRunId = EmptyChecks.isNull(rawWorkflowRunId)
          ? null
          : String.valueOf(message.schedulingContext().workflowRunId());
      BatchMdc.put(StructuredLogField.WORKFLOW_RUN_ID, mdcWorkflowRunId);
      Object rawBizDate = message.schedulingContext().bizDate();
      String mdcBizDate = EmptyChecks.isNull(rawBizDate)
          ? null
          : message.schedulingContext().bizDate().toString();
      BatchMdc.put(StructuredLogField.BATCH_DAY, mdcBizDate);
      Object rawAttempt = message.schedulingContext().attemptNo();
      String mdcAttempt = EmptyChecks.isNull(rawAttempt)
          ? null
          : String.valueOf(message.schedulingContext().attemptNo());
      BatchMdc.put(StructuredLogField.ATTEMPT, mdcAttempt);
    }
    BatchMdc.put(StructuredLogField.WORKER_TYPE, message.workerType());
    BatchMdc.put(StructuredLogField.WORKER_ID, workerId);
  }

  /** 从 message(task key + 路由元数据)+ CLAIM 返回的 effective config(业务字段)组装 PulledTask。 */
  private PulledTask buildTask(
      TaskDispatchMessage message, String workerId, EffectiveTaskConfig effective) {
    PulledTask task = new PulledTask();
    // task key:从 message 直读(消息携带就够了,与 effective 等价)
    task.setTaskId(String.valueOf(message.taskId()));
    task.setTenantId(message.tenantId());
    task.setWorkerId(workerId);
    task.setJobCode(message.jobCode());
    task.setTraceId(message.traceId());
    task.setIdempotencyKey(message.idempotencyKey());
    task.setJobInstanceId(message.jobInstanceId());
    task.setJobPartitionId(message.jobPartitionId());
    if (message.schedulingContext() != null) {
      task.setBizDate(message.schedulingContext().bizDate());
    }
    // 业务字段:从 CLAIM 返回的 effective config 读取；payload 在 claim 时读取，执行策略优先使用实例快照。
    task.setTaskType(effective.taskType());
    task.setBusinessKey(effective.businessKey());
    task.setTaskSeq(effective.taskSeq());
    task.setPayload(effective.payload());
    task.setHighWaterMarkIn(effective.highWaterMarkIn());
    task.setPartitionNo(effective.partitionNo());
    task.setPartitionCount(effective.partitionCount());
    task.setPartitionKey(effective.partitionKey());
    task.setPartitionPlanVersion(effective.partitionPlanVersion());
    task.setShardIndex(effective.shardIndex());
    task.setShardTotal(effective.shardTotal());
    task.setRangeStartInclusive(effective.rangeStartInclusive());
    task.setRangeEndExclusive(effective.rangeEndExclusive());
    task.setExpectedRows(effective.expectedRows());
    task.setPartitionInvocationId(effective.partitionInvocationId());
    task.setDependencyResultSnapshot(effective.dependencyResultSnapshot());
    task.setReplayExecutionGuard(effective.replayExecutionGuard());
    task.setTimeoutSeconds(effective.timeoutSeconds());
    // V94: data_interval 透传给 worker, 业务 SQL 拼时间窗
    task.setDataIntervalStart(effective.dataIntervalStart());
    task.setDataIntervalEnd(effective.dataIntervalEnd());
    return task;
  }
}
