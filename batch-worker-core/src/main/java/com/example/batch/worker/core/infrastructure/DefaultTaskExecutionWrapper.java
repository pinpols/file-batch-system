package com.example.batch.worker.core.infrastructure;

import com.example.batch.common.utils.JsonUtils;
import com.example.batch.worker.core.domain.TaskExecutionReport;
import com.example.batch.worker.core.domain.PulledTask;
import com.example.batch.worker.core.domain.StepExecutionRequest;
import com.example.batch.worker.core.domain.StepExecutionResponse;
import com.example.batch.worker.core.domain.WorkerExecutionResult;
import com.example.batch.worker.core.support.StepExecutionAdapter;
import com.example.batch.worker.core.support.TaskExecutionClient;
import com.example.batch.worker.core.support.TaskExecutionWrapper;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DefaultTaskExecutionWrapper implements TaskExecutionWrapper {

    private final StepExecutionAdapter stepExecutionAdapter;
    private final TaskExecutionClient taskExecutionClient;
    private final ActiveTaskLeaseRegistry activeTaskLeaseRegistry;

    @Override
    public boolean claim(String tenantId, Long taskId, String workerId) {
        return taskExecutionClient.claim(tenantId, taskId, workerId);
    }

    @Override
    public WorkerExecutionResult execute(PulledTask task) {
        Map<String, Object> executionContext = new LinkedHashMap<>();
        executionContext.put("payload", task.getPayload() == null ? "" : task.getPayload());
        executionContext.put("taskId", task.getTaskId());
        executionContext.put("workerId", task.getWorkerId());
        executionContext.put("jobCode", task.getJobCode() == null ? task.getTaskType() : task.getJobCode());
        executionContext.put("businessKey", task.getBusinessKey() == null ? "" : task.getBusinessKey());
        executionContext.put(PipelineRuntimeKeys.TRACE_ID, task.getTraceId() == null ? "" : task.getTraceId());
        executionContext.put(PipelineRuntimeKeys.JOB_INSTANCE_ID, task.getJobInstanceId());
        executionContext.put("jobPartitionId", task.getJobPartitionId());
        executionContext.put("taskSeq", task.getTaskSeq());
        executionContext.put("idempotencyKey", task.getIdempotencyKey() == null ? "" : task.getIdempotencyKey());
        StepExecutionRequest request = new StepExecutionRequest(
                task.getTenantId(),
                task.getJobCode(),
                task.getTaskType(),
                task.getWorkerId(),
                executionContext
        );
        activeTaskLeaseRegistry.register(task.getTaskId(), task.getTenantId(), task.getWorkerId());
        try {
            StepExecutionResponse response = stepExecutionAdapter.execute(request);
            TaskExecutionReport report = new TaskExecutionReport();
            report.setTaskId(Long.valueOf(task.getTaskId()));
            report.setTenantId(task.getTenantId());
            report.setWorkerId(task.getWorkerId());
            report.setSuccess(response.success());
            report.setCode(response.code());
            report.setMessage(response.message());
            if (!response.success()) {
                report.setErrorCode(response.code());
                report.setErrorMessage(response.message());
            }
            report.setResultSummary(JsonUtils.toJson(Map.of(
                    "code", response.code(),
                    "message", response.message())));
            taskExecutionClient.report(report);
            return new WorkerExecutionResult(task.getTaskId(), response.success(), response.message());
        } finally {
            activeTaskLeaseRegistry.remove(task.getTaskId());
        }
    }
}
