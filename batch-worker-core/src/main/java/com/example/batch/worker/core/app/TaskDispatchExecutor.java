package com.example.batch.worker.core.app;

import com.example.batch.common.kafka.TaskDispatchMessage;
import com.example.batch.worker.core.domain.PulledTask;
import com.example.batch.worker.core.domain.WorkerExecutionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TaskDispatchExecutor {

    private final WorkerRuntimeFacade workerRuntimeFacade;

    /**
     * Kafka 只负责把任务送到 worker，实际执行前仍然要回 Orchestrator 做 CLAIM。
     */
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
