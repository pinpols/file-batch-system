package com.example.batch.worker.core.application;

import com.example.batch.common.dto.EffectiveTaskConfig;
import com.example.batch.common.kafka.TaskDispatchMessage;
import com.example.batch.worker.core.domain.PulledTask;
import com.example.batch.worker.core.domain.WorkerExecutionResult;
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
    EffectiveTaskConfig effective = claimed.get();
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
    // 业务字段:从 CLAIM 返回的 effective config 读(确保管理员改完 retry/payload 立即生效)
    task.setTaskType(effective.taskType());
    task.setBusinessKey(effective.businessKey());
    task.setTaskSeq(effective.taskSeq());
    task.setPayload(effective.payload());
    task.setHighWaterMarkIn(effective.highWaterMarkIn());
    task.setPartitionNo(effective.partitionNo());
    task.setPartitionCount(effective.partitionCount());
    task.setPartitionKey(effective.partitionKey());
    return workerRuntimeFacade.execute(task);
  }
}
