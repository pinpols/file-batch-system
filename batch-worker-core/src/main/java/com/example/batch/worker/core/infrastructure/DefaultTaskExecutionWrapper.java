package com.example.batch.worker.core.infrastructure;

import com.example.batch.common.context.RunModeSupport;
import com.example.batch.common.dto.EffectiveTaskConfig;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.worker.core.config.WorkerExecutionTimeoutProperties;
import com.example.batch.worker.core.domain.PulledTask;
import com.example.batch.worker.core.domain.StepExecutionRequest;
import com.example.batch.worker.core.domain.StepExecutionResponse;
import com.example.batch.worker.core.domain.TaskExecutionReport;
import com.example.batch.worker.core.domain.WorkerExecutionResult;
import com.example.batch.worker.core.support.StepExecutionAdapter;
import com.example.batch.worker.core.support.TaskExecutionClient;
import com.example.batch.worker.core.support.TaskExecutionWrapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 单任务执行包装器:将 {@link PulledTask} 转换为 {@link StepExecutionRequest}, 驱动业务 pipeline, 并将执行结果通过 {@link
 * TaskExecutionClient#report} 上报 Orchestrator.
 *
 * <p><b>P0-1 (2026-05-03)</b>: 业务执行 submit 到 {@link TaskExecutionPool} 独立线程, listener 线程仅 {@code
 * future.get(timeout)} 等结果. 之前 plugin 死循环 / 长 SQL 卡住 → orchestrator 标 TIMED_OUT, 但 worker 线程仍占 着,
 * Semaphore permit 永不释放 → worker 容量永久缩水. 现在超时即 {@code future.cancel(true)} → 释放 permit + 上报
 * orchestrator 失败 (errorCode=WORKER_EXECUTION_TIMEOUT). 协作式 cancel: 业务线程通过 {@code
 * Thread.isInterrupted()} / 阻塞 IO 自动响应; 不响应中断的线程在 {@link
 * WorkerExecutionTimeoutProperties#getCancelGraceSeconds()} 后被 watchdog 标记泄漏 (metric {@code
 * worker.task.execution.thread.leaked.total}, 不影响 listener 派下个 task).
 *
 * <p><b>生命周期</b>:
 *
 * <ol>
 *   <li>在 {@link ActiveTaskLeaseRegistry} 注册任务租约 (供 {@link WorkerTaskLeaseRenewer} 定时续租).
 *   <li>调用 {@link StepExecutionAdapter#execute} 执行业务 pipeline (在 pool 线程, 限时阻塞).
 *   <li>无论成功 / 失败 / 超时, finally 块内移除租约并上报结果——不存在 "执行完成但不上报" 的状态.
 * </ol>
 */
@Slf4j
@Service
public class DefaultTaskExecutionWrapper implements TaskExecutionWrapper {

  static final String TIMEOUT_ERROR_CODE = "WORKER_EXECUTION_TIMEOUT";

  private final StepExecutionAdapter stepExecutionAdapter;
  private final TaskExecutionClient taskExecutionClient;
  private final ActiveTaskLeaseRegistry activeTaskLeaseRegistry;
  private final TaskExecutionPool executionPool;
  private final WorkerExecutionTimeoutProperties timeoutProperties;
  private final Counter timeoutCounter;
  private final Counter threadLeakedCounter;
  private final Timer executionTimer;

  private ScheduledExecutorService watchdog;

  public DefaultTaskExecutionWrapper(
      StepExecutionAdapter stepExecutionAdapter,
      TaskExecutionClient taskExecutionClient,
      ActiveTaskLeaseRegistry activeTaskLeaseRegistry,
      TaskExecutionPool executionPool,
      WorkerExecutionTimeoutProperties timeoutProperties,
      ObjectProvider<MeterRegistry> meterRegistryProvider) {
    this.stepExecutionAdapter = stepExecutionAdapter;
    this.taskExecutionClient = taskExecutionClient;
    this.activeTaskLeaseRegistry = activeTaskLeaseRegistry;
    this.executionPool = executionPool;
    this.timeoutProperties = timeoutProperties;
    MeterRegistry registry = meterRegistryProvider.getIfAvailable();
    if (registry == null) {
      this.timeoutCounter = null;
      this.threadLeakedCounter = null;
      this.executionTimer = null;
    } else {
      this.timeoutCounter =
          Counter.builder("worker.task.execution.timeout.total")
              .description("worker task 执行超时累计 (future.cancel(true) 触发)")
              .register(registry);
      this.threadLeakedCounter =
          Counter.builder("worker.task.execution.thread.leaked.total")
              .description("超时后 cancelGraceSeconds 内仍未退出的执行线程数 (业务线程不响应 interrupt)")
              .register(registry);
      this.executionTimer =
          Timer.builder("worker.task.execution.duration")
              .description("worker task 执行耗时分位")
              .tags(Tags.empty())
              .publishPercentiles(0.5, 0.95, 0.99)
              .register(registry);
    }
  }

  @PostConstruct
  void initWatchdog() {
    AtomicLong index = new AtomicLong();
    this.watchdog =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable);
              thread.setName("worker-task-cancel-watchdog-" + index.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            });
  }

  @PreDestroy
  void shutdownWatchdog() {
    if (watchdog != null) {
      watchdog.shutdownNow();
    }
  }

  @Override
  public Optional<EffectiveTaskConfig> claim(String tenantId, Long taskId, String workerId) {
    return taskExecutionClient.claim(tenantId, taskId, workerId);
  }

  @Override
  public WorkerExecutionResult execute(PulledTask task) {
    Map<String, Object> executionContext = buildExecutionContext(task);
    StepExecutionRequest request =
        new StepExecutionRequest(
            task.getTenantId(),
            task.getJobCode(),
            task.getTaskType(),
            task.getWorkerId(),
            executionContext);
    activeTaskLeaseRegistry.register(task.getTaskId(), task.getTenantId(), task.getWorkerId());
    long timeoutSeconds = resolveEffectiveTimeoutSeconds(task);
    long startNanos = System.nanoTime();
    try {
      StepExecutionResponse response = runWithTimeout(request, task, timeoutSeconds);
      TaskExecutionReport report =
          buildReport(task, response, executionContext, response.success());
      taskExecutionClient.report(report);
      return new WorkerExecutionResult(task.getTaskId(), response.success(), response.message());
    } finally {
      activeTaskLeaseRegistry.remove(task.getTaskId());
      if (executionTimer != null) {
        executionTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
      }
    }
  }

  /**
   * 把业务 pipeline 跑在 {@link TaskExecutionPool} 上, 主线程 (Kafka listener) 限时等待结果. 超时即 {@code
   * future.cancel(true)} 释放 listener, 业务线程靠 {@link Thread#isInterrupted()} / 阻塞 IO 协作退出; 不退出的线程
   * watchdog 记账放任 (worker 不会自动重启, 留给运维).
   */
  private StepExecutionResponse runWithTimeout(
      StepExecutionRequest request, PulledTask task, long timeoutSeconds) {
    Future<StepExecutionResponse> future =
        executionPool.submit(() -> stepExecutionAdapter.execute(request));
    try {
      return future.get(timeoutSeconds, TimeUnit.SECONDS);
    } catch (TimeoutException ex) {
      future.cancel(true);
      if (timeoutCounter != null) {
        timeoutCounter.increment();
      }
      log.error(
          "task execution timed out: tenantId={}, taskId={}, timeoutSeconds={} — issued"
              + " cancel(true)",
          task.getTenantId(),
          task.getTaskId(),
          timeoutSeconds);
      scheduleThreadLeakWatchdog(task, future);
      return new StepExecutionResponse(
          false,
          TIMEOUT_ERROR_CODE,
          "task execution exceeded " + timeoutSeconds + "s and was cancelled by worker",
          null,
          null);
    } catch (ExecutionException ex) {
      // 业务异常已在 stepExecutionAdapter 里被包成 StepExecutionResponse.failure 返回, 这里到达说明 adapter 自己抛了
      // RuntimeException (典型: 解析 payload 失败 / 框架 bug). 也按失败上报.
      Throwable cause = ex.getCause() == null ? ex : ex.getCause();
      log.error(
          "task execution adapter threw: tenantId={}, taskId={}",
          task.getTenantId(),
          task.getTaskId(),
          cause);
      return new StepExecutionResponse(
          false,
          "WORKER_EXECUTION_ERROR",
          cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(),
          null,
          null);
    } catch (InterruptedException ex) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new IllegalStateException("listener thread interrupted while waiting task", ex);
    }
  }

  /**
   * 派发独立 watchdog: cancelGraceSeconds 后检查 future 是否已 done, 没 done 即记账 (说明业务线程不响应 interrupt). 不会强杀线程
   * (Java 没有安全的强杀); 留给运维通过 metric 告警 + 重启 worker 兜底.
   */
  private void scheduleThreadLeakWatchdog(PulledTask task, Future<?> future) {
    long graceSeconds = Math.max(1L, timeoutProperties.getCancelGraceSeconds());
    if (watchdog == null) {
      return;
    }
    watchdog.schedule(
        () -> {
          if (!future.isDone()) {
            if (threadLeakedCounter != null) {
              threadLeakedCounter.increment();
            }
            log.error(
                "task execution thread did NOT exit within cancel grace ({}s): tenantId={},"
                    + " taskId={} — pool thread leaked, consider worker restart",
                graceSeconds,
                task.getTenantId(),
                task.getTaskId());
          }
        },
        graceSeconds,
        TimeUnit.SECONDS);
  }

  private long resolveEffectiveTimeoutSeconds(PulledTask task) {
    Integer fromConfig = task.getTimeoutSeconds();
    long base =
        (fromConfig == null || fromConfig <= 0)
            ? timeoutProperties.getDefaultTimeoutSeconds()
            : fromConfig;
    long max = timeoutProperties.getMaxTimeoutSeconds();
    if (max > 0 && base > max) {
      log.warn(
          "task timeoutSeconds {} exceeds max {}, clamping: tenantId={}, taskId={}",
          base,
          max,
          task.getTenantId(),
          task.getTaskId());
      return max;
    }
    return base;
  }

  private Map<String, Object> buildExecutionContext(PulledTask task) {
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
    if (task.getHighWaterMarkIn() != null) {
      // INCREMENTAL pipeline 业务读 attributes 拼 SQL 水位条件;FULL/CDC/历史首跑为 null。
      executionContext.put(PipelineRuntimeKeys.HIGH_WATER_MARK_IN, task.getHighWaterMarkIn());
    }
    // partition 信息透传:worker step / plugin 据此 + PARTITION_COUNT 决定切哪部分。null 时按"不分片"处理(下游
    // ParseStep 等会兜底为 1/1)。
    if (task.getPartitionNo() != null) {
      executionContext.put(PipelineRuntimeKeys.PARTITION_NO, task.getPartitionNo());
    }
    if (task.getPartitionCount() != null) {
      executionContext.put(PipelineRuntimeKeys.PARTITION_COUNT, task.getPartitionCount());
    }
    if (task.getPartitionKey() != null) {
      executionContext.put(PipelineRuntimeKeys.PARTITION_KEY, task.getPartitionKey());
    }
    return executionContext;
  }

  private TaskExecutionReport buildReport(
      PulledTask task,
      StepExecutionResponse response,
      Map<String, Object> executionContext,
      boolean success) {
    TaskExecutionReport report = new TaskExecutionReport();
    report.setTaskId(Long.valueOf(task.getTaskId()));
    report.setTenantId(task.getTenantId());
    report.setWorkerId(task.getWorkerId());
    report.setSuccess(success);
    report.setCode(response.code());
    report.setMessage(response.message());
    if (!success) {
      report.setErrorCode(response.code());
      report.setErrorMessage(response.message());
      // i18n 跨进程透传:plugin 用 StepExecutionResponse.failure(BizException, mapper)
      // 时,key/args 会一直传到 orchestrator 持久化。第三方异常 / literal 失败时为 null,orchestrator 仅用 message。
      report.setErrorKey(response.errorKey());
      report.setErrorArgs(response.errorArgs());
    }
    // 将 traceId 传递给 Orchestrator,确保状态更新与重试/DLQ 全链路可追踪
    report.setTraceId(task.getTraceId());
    report.setResultSummary(
        JsonUtils.toJson(
            Map.of(
                "code", response.code(),
                "message", response.message())));
    // INCREMENTAL pipeline 在 attributes 写出新水位 → 透传给 orchestrator;
    // 业务没显式写就保持 null,orchestrator 跳过持久化(保留旧值)。
    Object highWaterMarkOut = executionContext.get(PipelineRuntimeKeys.HIGH_WATER_MARK_OUT);
    if (highWaterMarkOut != null) {
      report.setHighWaterMarkOut(String.valueOf(highWaterMarkOut));
    }
    // ADR-009 Stage 1.2: worker adapter 在 attributes 写 NODE_OUTPUTS → 透传给 orchestrator,
    // success 路径持久化到 workflow_node_run.output。仅成功路径上报,失败路径不附带 outputs(语义不清)。
    if (success) {
      Object nodeOutputs = executionContext.get(PipelineRuntimeKeys.NODE_OUTPUTS);
      if (nodeOutputs instanceof Map<?, ?> outputsMap && !outputsMap.isEmpty()) {
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) outputsMap;
        report.setOutputs(typed);
      }
    }
    return report;
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
