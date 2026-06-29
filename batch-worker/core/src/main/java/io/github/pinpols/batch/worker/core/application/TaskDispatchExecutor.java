package io.github.pinpols.batch.worker.core.application;

import io.github.pinpols.batch.common.dto.EffectiveTaskConfig;
import io.github.pinpols.batch.common.kafka.TaskDispatchMessage;
import io.github.pinpols.batch.worker.core.domain.PulledTask;
import io.github.pinpols.batch.worker.core.domain.WorkerExecutionResult;
import io.github.pinpols.batch.worker.core.support.TaskClaimItem;
import io.github.pinpols.batch.worker.core.support.TaskClaimResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Kafka 消费层与 Worker 执行层之间的粘合：先向 Orchestrator CLAIM 任务所有权， 成功后再执行；CLAIM 失败（如已被其他 worker 抢占）则直接返回
 * null，消费层据此跳过。
 *
 * <p>保证 Orchestrator 是唯一状态主机：Kafka 仅负责任务路由，Worker 不能绕过 CLAIM 直接执行。
 *
 * <p>P1-2.2 起 message v2 已瘦身,业务字段(payload/businessKey/taskSeq/highWaterMarkIn 等) 全部从 CLAIM 返回的
 * {@link EffectiveTaskConfig} 实时读,本类不再 fallback 到 message。task key +
 * 路由元数据(tenantId/taskId/instanceId 等)继续从 message 读。
 */
@Service
@RequiredArgsConstructor
public class TaskDispatchExecutor {

  private final WorkerRuntimeFacade workerRuntimeFacade;

  /** Kafka 只负责把任务送到 worker，实际执行前仍然要回 Orchestrator 做 CLAIM。 */
  public WorkerExecutionResult execute(TaskDispatchMessage message, String workerId) {
    if (message == null || message.taskId() == null || workerId == null || workerId.isBlank()) {
      return null;
    }
    Optional<EffectiveTaskConfig> claimed =
        workerRuntimeFacade.claim(message.tenantId(), message.taskId(), workerId);
    if (claimed.isEmpty()) {
      return null;
    }
    return workerRuntimeFacade.execute(buildTask(message, workerId, claimed.get()));
  }

  /**
   * ADR-046 P2 切片 2.3b:批量执行 —— 一次 {@code claim-batch} 认领 K 个独立 task(CLAIM 往返 O(N)→O(N/K)),
   * 对**领到**的逐个独立执行(沿用 {@link #execute} 的 per-task 执行与上报语义),返回逐项结果。
   *
   * <p>没领到的项(被抢/不可领/不存在)跳过、不计入结果,与单条 {@link #execute} 返 null「消费层跳过」一致。 **本步只批量化 CLAIM**;每个 task 的执行
   * + 上报仍 per-task(report 批量化需拆执行包装器,见 2.3 施工方案后续)。 仅供 2.3c 的批量 listener 调用;flag 关时无调用方。
   */
  public List<WorkerExecutionResult> executeBatch(
      List<TaskDispatchMessage> messages, String workerId) {
    if (messages == null || messages.isEmpty() || workerId == null || workerId.isBlank()) {
      return List.of();
    }
    List<TaskClaimItem> items = new ArrayList<>(messages.size());
    for (TaskDispatchMessage m : messages) {
      if (m != null && m.taskId() != null) {
        items.add(new TaskClaimItem(m.tenantId(), m.taskId(), workerId));
      }
    }
    if (items.isEmpty()) {
      return List.of();
    }
    Map<Long, TaskClaimResult> claimedById = new LinkedHashMap<>();
    for (TaskClaimResult r : workerRuntimeFacade.claimBatch(items)) {
      if (r != null && r.taskId() != null) {
        claimedById.put(r.taskId(), r);
      }
    }
    List<WorkerExecutionResult> results = new ArrayList<>();
    for (TaskDispatchMessage message : messages) {
      if (message == null || message.taskId() == null) {
        continue;
      }
      TaskClaimResult claim = claimedById.get(message.taskId());
      if (claim == null || !claim.claimed() || claim.config() == null) {
        continue; // 没领到 → 跳过(与单条 execute 返 null 一致)
      }
      results.add(workerRuntimeFacade.execute(buildTask(message, workerId, claim.config())));
    }
    return results;
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
    // 业务字段:从 CLAIM 返回的 effective config 读(确保管理员改完 retry/payload 立即生效)
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
    task.setTimeoutSeconds(effective.timeoutSeconds());
    // V94: data_interval 透传给 worker, 业务 SQL 拼时间窗
    task.setDataIntervalStart(effective.dataIntervalStart());
    task.setDataIntervalEnd(effective.dataIntervalEnd());
    return task;
  }
}
