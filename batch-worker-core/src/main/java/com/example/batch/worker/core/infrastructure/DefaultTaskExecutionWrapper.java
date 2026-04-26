package com.example.batch.worker.core.infrastructure;

import com.example.batch.common.context.RunModeSupport;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.worker.core.domain.PulledTask;
import com.example.batch.worker.core.domain.StepExecutionRequest;
import com.example.batch.worker.core.domain.StepExecutionResponse;
import com.example.batch.worker.core.domain.TaskExecutionReport;
import com.example.batch.worker.core.domain.WorkerExecutionResult;
import com.example.batch.worker.core.support.StepExecutionAdapter;
import com.example.batch.worker.core.support.TaskExecutionClient;
import com.example.batch.worker.core.support.TaskExecutionWrapper;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 单任务执行包装器：将 {@link PulledTask} 转换为 {@link StepExecutionRequest}， 驱动业务 pipeline，并将执行结果通过 {@link
 * TaskExecutionClient#report} 上报 Orchestrator。
 *
 * <p><b>生命周期</b>：
 *
 * <ol>
 *   <li>在 {@link ActiveTaskLeaseRegistry} 注册任务租约（供 {@link WorkerTaskLeaseRenewer} 定时续租， 防止
 *       Orchestrator 因心跳超时误判任务失活并重新派发）。
 *   <li>调用 {@link StepExecutionAdapter#execute} 执行业务 pipeline（阻塞）。
 *   <li>无论成功或失败，finally 块内移除租约并上报结果——不存在"执行完成但不上报"的状态。
 * </ol>
 */
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
    String payload = task.getPayload() == null ? "" : task.getPayload();
    executionContext.put("payload", payload);
    executionContext.put("taskId", task.getTaskId());
    executionContext.put("workerId", task.getWorkerId());
    executionContext.put(
        "jobCode", task.getJobCode() == null ? task.getTaskType() : task.getJobCode());
    executionContext.put("businessKey", task.getBusinessKey() == null ? "" : task.getBusinessKey());
    executionContext.put(
        PipelineRuntimeKeys.TRACE_ID, task.getTraceId() == null ? "" : task.getTraceId());
    executionContext.put(PipelineRuntimeKeys.JOB_INSTANCE_ID, task.getJobInstanceId());
    executionContext.put("jobPartitionId", task.getJobPartitionId());
    executionContext.put("taskSeq", task.getTaskSeq());
    executionContext.put(
        "idempotencyKey", task.getIdempotencyKey() == null ? "" : task.getIdempotencyKey());
    String runMode = resolveRunMode(payload);
    if (runMode != null) {
      executionContext.put(PipelineRuntimeKeys.RUN_MODE, runMode);
    }
    StepExecutionRequest request =
        new StepExecutionRequest(
            task.getTenantId(),
            task.getJobCode(),
            task.getTaskType(),
            task.getWorkerId(),
            executionContext);
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
      // 将 traceId 传递给 Orchestrator，确保状态更新与重试/DLQ 全链路可追踪
      report.setTraceId(task.getTraceId());
      report.setResultSummary(
          JsonUtils.toJson(
              Map.of(
                  "code", response.code(),
                  "message", response.message())));
      taskExecutionClient.report(report);
      return new WorkerExecutionResult(task.getTaskId(), response.success(), response.message());
    } finally {
      activeTaskLeaseRegistry.remove(task.getTaskId());
    }
  }

  @SuppressWarnings("unchecked")
  private String resolveRunMode(String payload) {
    if (payload == null || payload.isBlank()) {
      return null;
    }
    try {
      Object payloadObject = JsonUtils.fromJson(payload, Object.class);
      if (payloadObject instanceof Map<?, ?> payloadMap) {
        return RunModeSupport.resolveCode((Map<String, Object>) payloadMap);
      }
    } catch (RuntimeException ignored) {
      // payload 非合法 JSON 时不设置 run_mode。
    }
    return null;
  }
}
