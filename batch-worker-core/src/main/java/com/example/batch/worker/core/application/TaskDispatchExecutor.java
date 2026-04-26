package com.example.batch.worker.core.application;

import com.example.batch.common.kafka.TaskDispatchMessage;
import com.example.batch.worker.core.domain.PulledTask;
import com.example.batch.worker.core.domain.WorkerExecutionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Kafka 消费层与 Worker 执行层之间的粘合：先向 Orchestrator CLAIM 任务所有权， 成功后再执行；CLAIM 失败（如已被其他 worker 抢占）则直接返回
 * null，消费层据此跳过。
 *
 * <p>保证 Orchestrator 是唯一状态主机：Kafka 仅负责任务路由，Worker 不能绕过 CLAIM 直接执行。
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
    if (!workerRuntimeFacade.claim(message.tenantId(), message.taskId(), workerId)) {
      return null;
    }
    PulledTask task = new PulledTask();
    task.setTaskId(String.valueOf(message.taskId()));
    task.setTaskType(message.taskType());
    task.setJobCode(message.jobCode());
    task.setTenantId(message.tenantId());
    task.setWorkerId(workerId);
    task.setTraceId(message.traceId());
    task.setBusinessKey(message.businessKey());
    task.setJobInstanceId(message.jobInstanceId());
    task.setJobPartitionId(message.jobPartitionId());
    task.setTaskSeq(message.taskSeq());
    task.setIdempotencyKey(message.idempotencyKey());
    task.setPayload(message.payload());
    return workerRuntimeFacade.execute(task);
  }
}
