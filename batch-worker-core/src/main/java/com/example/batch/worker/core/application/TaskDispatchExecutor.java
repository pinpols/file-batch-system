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
 * <p>P1-2.1:CLAIM 成功时 orchestrator 回 {@link EffectiveTaskConfig} 快照,本类拼 {@link PulledTask}时优先 读
 * response 字段(确保管理员改完 retry/timeout 立即生效),缺字段时 fallback 到 message 旧字段(对接旧 orchestrator 部署的过渡场景)。
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
    task.setTaskId(String.valueOf(message.taskId()));
    task.setTaskType(preferConfig(effective.taskType(), message.taskType()));
    task.setJobCode(preferConfig(effective.jobCode(), message.jobCode()));
    task.setTenantId(message.tenantId());
    task.setWorkerId(workerId);
    task.setTraceId(preferConfig(effective.traceId(), message.traceId()));
    task.setBusinessKey(preferConfig(effective.businessKey(), message.businessKey()));
    task.setJobInstanceId(message.jobInstanceId());
    task.setJobPartitionId(message.jobPartitionId());
    task.setTaskSeq(preferConfig(effective.taskSeq(), message.taskSeq()));
    task.setIdempotencyKey(preferConfig(effective.idempotencyKey(), message.idempotencyKey()));
    task.setPayload(preferConfig(effective.payload(), message.payload()));
    task.setHighWaterMarkIn(preferConfig(effective.highWaterMarkIn(), message.highWaterMarkIn()));
    return workerRuntimeFacade.execute(task);
  }

  /** P1-2.1:优先 response,空时 fallback 到 message。 */
  private static <T> T preferConfig(T fromConfig, T fromMessage) {
    return fromConfig != null ? fromConfig : fromMessage;
  }
}
