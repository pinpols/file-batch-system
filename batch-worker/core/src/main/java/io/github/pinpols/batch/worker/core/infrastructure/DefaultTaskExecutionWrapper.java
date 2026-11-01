package io.github.pinpols.batch.worker.core.infrastructure;

import io.github.pinpols.batch.common.context.RunModeSupport;
import io.github.pinpols.batch.common.dto.EffectiveTaskConfig;
import io.github.pinpols.batch.common.dto.ReplayExecutionGuard;
import io.github.pinpols.batch.common.enums.TaskErrorCodes;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.plugin.ReplayCapabilityDeclaration;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.worker.core.config.WorkerCoreAsyncConfiguration;
import io.github.pinpols.batch.worker.core.config.WorkerExecutionTimeoutProperties;
import io.github.pinpols.batch.worker.core.config.WorkerLeaseProperties;
import io.github.pinpols.batch.worker.core.domain.PulledTask;
import io.github.pinpols.batch.worker.core.domain.StepExecutionRequest;
import io.github.pinpols.batch.worker.core.domain.StepExecutionResponse;
import io.github.pinpols.batch.worker.core.domain.TaskExecutionReport;
import io.github.pinpols.batch.worker.core.domain.WorkerExecutionResult;
import io.github.pinpols.batch.worker.core.support.StepExecutionAdapter;
import io.github.pinpols.batch.worker.core.support.TaskClaimItem;
import io.github.pinpols.batch.worker.core.support.TaskClaimResult;
import io.github.pinpols.batch.worker.core.support.TaskExecutionClient;
import io.github.pinpols.batch.worker.core.support.TaskExecutionWrapper;
import io.github.pinpols.batch.worker.core.support.TaskLeaseRenewItem;
import io.github.pinpols.batch.worker.core.support.TaskLeaseRenewResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.annotation.Observed;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

/**
 * 单任务执行包装器：将 {@link PulledTask} 转换为 {@link StepExecutionRequest}，
 * 驱动业务流水线，并通过 {@link TaskExecutionClient#report} 上报执行结果。
 *
 * <p>业务在 {@link TaskExecutionPool} 的独立线程中执行，监听线程限时等待结果。
 * 超时后请求中断并生成 {@code WORKER_EXECUTION_TIMEOUT} 结果；业务代码需通过中断检查或阻塞 I/O 协作退出。
 * 超过 {@link WorkerExecutionTimeoutProperties#getCancelGraceSeconds()} 仍未退出的线程记入
 * {@code worker.task.execution.thread.leaked.total}，不能将取消 Future 等同于业务线程已经结束。
 *
 * <p>执行生命周期：
 *
 * <ol>
 *   <li>登记当前 invocation 的租约，由 {@link WorkerTaskLeaseRenewer} 负责周期续租。
 *   <li>执行池线程确认执行入口后，调用 {@link StepExecutionAdapter#execute} 执行业务。
 *   <li>仍持有租约时上报结果；确认失败或租约丢失时停止执行或上报，避免旧所有者覆盖当前状态。
 *   <li>退出执行包装器时清理本地租约登记；JVM 级故障保留原始异常，不转换为普通业务失败。
 * </ol>
 */
@Slf4j
@Service
@SuppressWarnings(
    "SpringJavaInjectionPointsAutowiringInspection") // 各独立 Worker classpath 仅含一个 @Primary Adapter。
public class DefaultTaskExecutionWrapper implements TaskExecutionWrapper {

  static final String TIMEOUT_ERROR_CODE = "WORKER_EXECUTION_TIMEOUT";
  static final String CANCELLED_ERROR_CODE = "WORKER_EXECUTION_CANCELLED";
  private static final String OPERATION_TYPE_PAYLOAD_KEY = "operationType";
  private static final String WORKFLOW_RUN_OPERATION_TYPE = "WORKFLOW_RUN";
  private static final String FAILURE_CLASS_TIMEOUT = "TIMEOUT";
  private static final String FAILURE_CLASS_CONFIG = "CONFIG";

  /** errorCode → failureClass 归一映射；未知错误码返回 null（不归类）。 */
  private static final Map<String, String> FAILURE_CLASS_BY_CODE = Map.ofEntries(
      Map.entry(TIMEOUT_ERROR_CODE, FAILURE_CLASS_TIMEOUT),
      Map.entry(FAILURE_CLASS_TIMEOUT, FAILURE_CLASS_TIMEOUT),
      Map.entry("CONFIG_INVALID", FAILURE_CLASS_CONFIG),
      Map.entry("DISPATCH_SECRET_BINDING_MISSING", FAILURE_CLASS_CONFIG),
      Map.entry("DISPATCH_SECRET_VERSION_MISSING", FAILURE_CLASS_CONFIG),
      Map.entry("DISPATCH_SECRET_VERSION_INVALID", FAILURE_CLASS_CONFIG),
      Map.entry("DISPATCH_SECRET_REVOKED_OR_EXPIRED", FAILURE_CLASS_CONFIG),
      Map.entry("DISPATCH_SECRET_FIELD_NOT_ALLOWED", FAILURE_CLASS_CONFIG),
      Map.entry("DISPATCH_SECRET_PAYLOAD_EMPTY", FAILURE_CLASS_CONFIG),
      Map.entry("DISPATCH_SECRET_PAYLOAD_INVALID", FAILURE_CLASS_CONFIG),
      Map.entry("SECURITY_REJECTED", FAILURE_CLASS_CONFIG),
      Map.entry("NO_EXECUTOR", FAILURE_CLASS_CONFIG),
      Map.entry(TaskErrorCodes.REPLAY_CAPABILITY_INVALID, FAILURE_CLASS_CONFIG),
      Map.entry("RESOURCE_EXHAUSTED", "INFRASTRUCTURE"),
      Map.entry(TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN, "UNKNOWN"),
      Map.entry("EXECUTOR_FAILURE", FAILURE_CLASS_CONFIG));

  private final StepExecutionAdapter stepExecutionAdapter;
  private final TaskExecutionClient taskExecutionClient;
  private final ActiveTaskLeaseRegistry activeTaskLeaseRegistry;
  private final TaskExecutionPool executionPool;
  private final WorkerExecutionTimeoutProperties timeoutProperties;
  private final Counter timeoutCounter;
  private final Counter threadLeakedCounter;
  // R3-P2-5：之前 executionTimer 用 Tags.empty()，4 类 worker 共享一条时间序列。
  // 改为按 workerType 维度懒加载 cache → Grafana 可分别看五类 worker 各自分位。
  private final MeterRegistry meterRegistry;
  private final Map<String, Timer> executionTimerByType = new ConcurrentHashMap<>();

  private final TaskScheduler watchdog;

  private final long leaseRenewalIntervalMillis;

  public DefaultTaskExecutionWrapper(
      StepExecutionAdapter stepExecutionAdapter,
      TaskExecutionClient taskExecutionClient,
      ActiveTaskLeaseRegistry activeTaskLeaseRegistry,
      TaskExecutionPool executionPool,
      WorkerExecutionTimeoutProperties timeoutProperties,
      ObjectProvider<MeterRegistry> meterRegistryProvider,
      @Qualifier(WorkerCoreAsyncConfiguration.WATCHDOG_SCHEDULER) TaskScheduler watchdog,
      WorkerLeaseProperties leaseProperties) {
    this.stepExecutionAdapter = stepExecutionAdapter;
    this.taskExecutionClient = taskExecutionClient;
    this.activeTaskLeaseRegistry = activeTaskLeaseRegistry;
    this.executionPool = executionPool;
    this.timeoutProperties = timeoutProperties;
    this.watchdog = watchdog;
    this.leaseRenewalIntervalMillis = Math.max(1L, leaseProperties.getRenewIntervalMillis());
    MeterRegistry registry = meterRegistryProvider.getIfAvailable();
    this.meterRegistry = registry;
    if (registry == null) {
      this.timeoutCounter = null;
      this.threadLeakedCounter = null;
    } else {
      this.timeoutCounter = Counter.builder("worker.task.execution.timeout.total")
          .description("worker task 执行超时累计 (future.cancel(true) 触发)")
          .register(registry);
      this.threadLeakedCounter = Counter.builder("worker.task.execution.thread.leaked.total")
          .description("超时后 cancelGraceSeconds 内仍未退出的执行线程数 (业务线程不响应 interrupt)")
          .register(registry);
    }
  }

  /** R3-P2-5：按 workerType 懒注册 Timer；同一 type 复用同一实例避免 cardinality 失控。 */
  private Timer resolveExecutionTimer(String workerType) {
    if (meterRegistry == null) {
      return null;
    }
    String tag = workerType == null || workerType.isBlank() ? "unknown" : workerType;
    return executionTimerByType.computeIfAbsent(
        tag,
        t -> Timer.builder("worker.task.execution.duration")
            .description("worker task 执行耗时分位")
            .tags(Tags.of("workerType", t))
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry));
  }

  /**
   * watchdog 生命周期由 Spring 管理 ({@link
   * io.github.pinpols.batch.worker.core.config.WorkerCoreAsyncConfiguration#workerWatchdogScheduler})。
   * 这里保留 {@code @PreDestroy} 钩子仅为兼容旧测试 (测试可单独触发关闭); 容器关闭时 Spring 也会调 scheduler 的 {@code
   * shutdown()}。
   */
  @PreDestroy
  void shutdownWatchdog() {
    // 无操作: scheduler 由 Spring 容器统一关闭, 这里保留方法便于测试钩子访问
  }

  @Override
  public Optional<EffectiveTaskConfig> claim(String tenantId, Long taskId, String workerId) {
    long claimStartedAtNanos = activeTaskLeaseRegistry.currentNanos();
    Optional<EffectiveTaskConfig> claimed = taskExecutionClient.claim(tenantId, taskId, workerId);
    if (claimed.isPresent()) {
      EffectiveTaskConfig config = claimed.get();
      activeTaskLeaseRegistry.registerClaimedAt(
          EmptyChecks.isNull(taskId) ? null : taskId.toString(),
          tenantId,
          workerId,
          config.partitionInvocationId(),
          claimStartedAtNanos);
    }
    return claimed;
  }

  @Override
  public List<TaskClaimResult> claimBatch(List<TaskClaimItem> items) {
    long claimStartedAtNanos = activeTaskLeaseRegistry.currentNanos();
    List<TaskClaimResult> results = taskExecutionClient.claimBatch(items);
    boolean claimBatchHasNoTrackableItems = EmptyChecks.isEmpty(results)
        || EmptyChecks.isEmpty(items);
    if (claimBatchHasNoTrackableItems) {
      return results;
    }
    Map<Long, TaskClaimItem> submittedByTaskId = new LinkedHashMap<>();
    for (TaskClaimItem item : items) {
      if (EmptyChecks.isNotNull(item) && EmptyChecks.isNotNull(item.taskId())) {
        submittedByTaskId.putIfAbsent(item.taskId(), item);
      }
    }
    for (TaskClaimResult result : results) {
      boolean claimedTaskConfigurationIsIncomplete = EmptyChecks.isNull(result)
          || !result.claimed()
          || EmptyChecks.isNull(result.taskId())
          || EmptyChecks.isNull(result.config());
      if (claimedTaskConfigurationIsIncomplete) {
        continue;
      }
      TaskClaimItem submitted = submittedByTaskId.get(result.taskId());
      if (EmptyChecks.isNotNull(submitted)) {
        activeTaskLeaseRegistry.registerClaimedAt(
            result.taskId().toString(),
            submitted.tenantId(),
            submitted.workerId(),
            result.config().partitionInvocationId(),
            claimStartedAtNanos);
      }
    }
    return results;
  }

  @Override
  public void registerClaimedBatch(List<PulledTask> tasks) {
    if (EmptyChecks.isEmpty(tasks)) {
      return;
    }
    for (PulledTask task : tasks) {
      if (EmptyChecks.isNotNull(task)) {
        activeTaskLeaseRegistry.registerClaimed(
            task.getTaskId(),
            task.getTenantId(),
            task.getWorkerId(),
            task.getPartitionInvocationId());
      }
    }
  }

  @Override
  public void releaseUnexecutedClaims(List<PulledTask> tasks) {
    if (EmptyChecks.isNull(tasks)) return;
    for (PulledTask task : tasks) {
      if (EmptyChecks.isNotNull(task)) {
        activeTaskLeaseRegistry.removeQueuedClaim(
            task.getTaskId(),
            task.getTenantId(),
            task.getWorkerId(),
            task.getPartitionInvocationId());
      }
    }
  }

  @Override
  @Observed(name = "worker.task.execute", contextualName = "worker.task.execute")
  public WorkerExecutionResult execute(PulledTask task) {
    if (EmptyChecks.isNull(task)
        || !activeTaskLeaseRegistry.beginExecution(
            task.getTaskId(),
            task.getTenantId(),
            task.getWorkerId(),
            task.getPartitionInvocationId())) {
      String taskId = EmptyChecks.isNull(task) ? null : task.getTaskId();
      log.warn(
          "task execution start rejected: lease already active or owner changed, taskId={}",
          taskId);
      return new WorkerExecutionResult(taskId, false, "task lease is not available for execution");
    }
    long startNanos = System.nanoTime();
    try {
      Map<String, Object> executionContext = buildExecutionContext(task);
      StepExecutionRequest request = new StepExecutionRequest(
          task.getTenantId(),
          task.getJobCode(),
          task.getTaskType(),
          task.getWorkerId(),
          executionContext);
      long timeoutSeconds = resolveEffectiveTimeoutSeconds(task);
      if (activeTaskLeaseRegistry.isLost(task.getTaskId()) || !confirmLeaseBeforeExecution(task)) {
        return leaseLostResult(task);
      }
      StepExecutionResponse response;
      if (activeTaskLeaseRegistry.isCancellationRequested(task.getTaskId())) {
        response = new StepExecutionResponse(
            false,
            CANCELLED_ERROR_CODE,
            "task execution cancelled by orchestrator request before execution");
      } else if (!replayCapabilityAllowsExecution(task, request)) {
        response = new StepExecutionResponse(
            false,
            TaskErrorCodes.REPLAY_CAPABILITY_INVALID,
            "worker replay capability does not satisfy the frozen replay contract");
      } else {
        response = runWithTimeout(request, task, timeoutSeconds);
      }
      // 续租 CAS 拒绝后禁止旧所有者回报。完成确认与租约丢失标记共用注册表写锁，
      // 防止已完成任务被迟到的旧续租结果错误标记为租约丢失。
      if (!activeTaskLeaseRegistry.markCompletingUnlessLost(task.getTaskId())) {
        log.error(
            "task lease lost during execution — aborting report: tenantId={}, taskId={},"
                + " workerId={}",
            task.getTenantId(),
            task.getTaskId(),
            task.getWorkerId());
        return new WorkerExecutionResult(
            task.getTaskId(), false, "lease lost (evicted by orchestrator); report aborted");
      }
      TaskExecutionReport report =
          buildReport(task, response, executionContext, response.success());
      taskExecutionClient.report(report);
      return new WorkerExecutionResult(task.getTaskId(), response.success(), response.message());
    } finally {
      activeTaskLeaseRegistry.remove(task.getTaskId());
      Timer timer = resolveExecutionTimer(task.getTaskType());
      if (timer != null) {
        timer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
      }
    }
  }

  /** 执行前按认领任务的精确配置重新解析重放能力。 */
  private boolean replayCapabilityAllowsExecution(PulledTask task, StepExecutionRequest request) {
    ReplayExecutionGuard guard = task.getReplayExecutionGuard();
    if (EmptyChecks.isNull(guard)) {
      if (isWorkflowReplayPayload(task.getPayload())) {
        log.warn(
            "workflow replay claim is missing capability guard; refusing execution:"
                + " tenantId={}, taskId={}",
            task.getTenantId(),
            task.getTaskId());
        return false;
      }
      return true;
    }
    try {
      ReplayCapabilityDeclaration actual = stepExecutionAdapter.resolveReplayCapability(request);
      if (guard.declaration().covers(actual)) {
        return true;
      }
      log.warn(
          "replay capability mismatch; refusing execution: tenantId={}, taskId={},"
              + " replayOperationId={}, declaredPluginId={}, actualPluginId={}",
          task.getTenantId(),
          task.getTaskId(),
          guard.replayOperationId(),
          guard.declaration().pluginId(),
          EmptyChecks.isNull(actual) ? null : actual.pluginId());
      return false;
    } catch (RuntimeException ex) {
      log.warn(
          "replay capability resolution failed; refusing execution: tenantId={}, taskId={},"
              + " replayOperationId={}, cause={}",
          task.getTenantId(),
          task.getTaskId(),
          guard.replayOperationId(),
          SwallowedExceptionLogger.summary(ex));
      return false;
    }
  }

  private boolean isWorkflowReplayPayload(String payload) {
    if (EmptyChecks.isBlank(payload)) {
      return false;
    }
    try {
      Object payloadObject = JsonUtils.fromJson(payload, Object.class);
      if (payloadObject instanceof Map<?, ?> payloadMap) {
        return WORKFLOW_RUN_OPERATION_TYPE.equals(payloadMap.get(OPERATION_TYPE_PAYLOAD_KEY));
      }
    } catch (RuntimeException ignored) {
      SwallowedExceptionLogger.warn(
          DefaultTaskExecutionWrapper.class, "catch:RuntimeException", ignored);
    }
    return false;
  }

  /**
   * 已认领任务连续错过续租窗口时，通过 Orchestrator 的 invocation CAS 重新确认租约。
   * 拒绝或无法确认时停止执行，由租约回收流程重新派发。
   */
  private boolean confirmLeaseBeforeExecution(PulledTask task) {
    if (activeTaskLeaseRegistry.isLost(task.getTaskId())) {
      return false;
    }
    long staleAfterMillis = leaseRenewalIntervalMillis > Long.MAX_VALUE / 2L
        ? Long.MAX_VALUE
        : leaseRenewalIntervalMillis * 2L;
    if (!activeTaskLeaseRegistry.isRenewalDue(task.getTaskId(), staleAfterMillis)) {
      return true;
    }
    Long renewalTaskId = Long.valueOf(task.getTaskId());
    TaskLeaseRenewItem renewalItem = new TaskLeaseRenewItem(
        task.getTenantId(), renewalTaskId, task.getWorkerId(), task.getPartitionInvocationId());
    return confirmLease(task, renewalItem);
  }

  /** 在执行池实际取得线程后确认执行起点，排队等待不计入业务执行耗时。 */
  private boolean confirmExecutionStart(PulledTask task) {
    if (activeTaskLeaseRegistry.isLost(task.getTaskId())) {
      return false;
    }
    Long taskId = Long.valueOf(task.getTaskId());
    TaskLeaseRenewItem startItem = TaskLeaseRenewItem.executionStart(
        task.getTenantId(), taskId, task.getWorkerId(), task.getPartitionInvocationId());
    boolean confirmed = confirmLease(task, startItem);
    if (!confirmed) {
      // 无法确认执行身份时禁止进入业务，也不能将确认失败上报为业务执行失败。
      activeTaskLeaseRegistry.markLost(task.getTaskId());
    }
    return confirmed;
  }

  private boolean confirmLease(PulledTask task, TaskLeaseRenewItem renewalItem) {
    try {
      Map<Long, TaskLeaseRenewResult> resultByTaskId =
          taskExecutionClient.renewLeasesBatch(List.of(renewalItem));
      TaskLeaseRenewResult result;
      if (EmptyChecks.isNull(resultByTaskId)) {
        result = null;
      } else {
        Long lookupTaskId = Long.valueOf(task.getTaskId());
        result = resultByTaskId.get(lookupTaskId);
      }
      if (EmptyChecks.isNull(result) || !result.renewed()) {
        activeTaskLeaseRegistry.markLost(task.getTaskId());
        log.warn(
            "task lease confirmation rejected before execution: tenantId={}, taskId={}, workerId={}",
            task.getTenantId(),
            task.getTaskId(),
            task.getWorkerId());
        return false;
      }
      activeTaskLeaseRegistry.markRenewed(task.getTaskId());
      if (result.cancelRequested()) {
        activeTaskLeaseRegistry.requestCancellation(task.getTaskId());
      }
      return true;
    } catch (RuntimeException ex) {
      log.warn(
          "task lease could not be confirmed before execution: tenantId={}, taskId={}, error={}",
          task.getTenantId(),
          task.getTaskId(),
          SwallowedExceptionLogger.summary(ex));
      return false;
    }
  }

  private static WorkerExecutionResult leaseLostResult(PulledTask task) {
    return new WorkerExecutionResult(
        task.getTaskId(),
        false,
        "lease lost or could not be confirmed; execution and report skipped");
  }

  /**
   * 业务流程在 {@link TaskExecutionPool} 中执行，Kafka 监听线程限时等待结果。超时后调用 {@code
   * future.cancel(true)}，业务线程通过中断或阻塞 I/O 协作退出；未退出线程由看门狗记录并交由运维处理。
   */
  private StepExecutionResponse runWithTimeout(
      StepExecutionRequest request, PulledTask task, long timeoutSeconds) {
    AtomicBoolean executing = new AtomicBoolean();
    Future<StepExecutionResponse> future;
    try {
      future = executionPool.submit(() -> {
        executing.set(true);
        try {
          if (!confirmExecutionStart(task)) {
            return new StepExecutionResponse(
                false, "LEASE_LOST", "execution start was not confirmed");
          }
          if (activeTaskLeaseRegistry.isCancellationRequested(task.getTaskId())
              || Thread.currentThread().isInterrupted()) {
            return new StepExecutionResponse(
                false, CANCELLED_ERROR_CODE, "task cancelled before adapter entry");
          }
          return stepExecutionAdapter.execute(request);
        } finally {
          executing.set(false);
        }
      });
    } catch (RejectedExecutionException rejected) {
      return new StepExecutionResponse(
          false, "RESOURCE_EXHAUSTED", "worker execution pool is saturated or shutting down");
    }
    activeTaskLeaseRegistry.registerCancellationCallback(task.getTaskId(), () -> {
      boolean cancelled = future.cancel(true);
      log.info(
          "task cancellation requested by orchestrator: tenantId={}, taskId={}," + " cancelled={}",
          task.getTenantId(),
          task.getTaskId(),
          cancelled);
    });
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
      scheduleThreadLeakWatchdog(task, executing);
      return new StepExecutionResponse(
          false,
          TIMEOUT_ERROR_CODE,
          "task execution exceeded " + timeoutSeconds + "s and was cancelled by worker");
    } catch (CancellationException ex) {
      scheduleThreadLeakWatchdog(task, executing);
      String message = activeTaskLeaseRegistry.isCancellationRequested(task.getTaskId())
          ? "task execution cancelled by orchestrator request"
          : "task execution cancelled";
      return new StepExecutionResponse(false, CANCELLED_ERROR_CODE, message);
    } catch (ExecutionException ex) {
      // 处理适配器未转换的执行异常；JVM 级故障继续抛出，其余异常转换为执行错误结果。
      Throwable cause = EmptyChecks.isNull(ex.getCause()) ? ex : ex.getCause();
      rethrowFatal(cause);
      log.error(
          "task execution adapter threw: tenantId={}, taskId={}",
          task.getTenantId(),
          task.getTaskId(),
          cause);
      String executionErrorMessage = EmptyChecks.isNull(cause.getMessage())
          ? cause.getClass().getSimpleName()
          : cause.getMessage();
      return new StepExecutionResponse(false, "WORKER_EXECUTION_ERROR", executionErrorMessage);
    } catch (InterruptedException ex) {
      future.cancel(true);
      scheduleThreadLeakWatchdog(task, executing);
      Thread.currentThread().interrupt();
      throw new IllegalStateException("listener thread interrupted while waiting task", ex);
    }
  }

  /** 后台执行线程的 JVM 级故障不能被包装成单个任务失败。 */
  private static void rethrowFatal(Throwable throwable) {
    if (throwable instanceof Error error) {
      throw error;
    }
  }

  /**
   * 宽限期后检查业务调用是否实际退出，不能仅依据 Future 的取消状态判断线程已结束。
   * 未退出的线程通过指标告警交由运维处置，避免使用不安全的强制终止操作。
   */
  private void scheduleThreadLeakWatchdog(PulledTask task, AtomicBoolean executing) {
    long graceSeconds = Math.max(1L, timeoutProperties.getCancelGraceSeconds());
    if (EmptyChecks.isNull(watchdog)) {
      return;
    }
    Runnable watchdogTask = () -> {
      if (executing.get()) {
        if (EmptyChecks.isNotNull(threadLeakedCounter)) {
          threadLeakedCounter.increment();
        }
        log.error(
            "task execution thread did NOT exit within cancel grace ({}s): tenantId={},"
                + " taskId={} — pool thread leaked, consider worker restart",
            graceSeconds,
            task.getTenantId(),
            task.getTaskId());
      }
    };
    Instant scheduledAt = Instant.now();
    Duration cancelGrace = Duration.ofSeconds(graceSeconds);
    Instant deadline = scheduledAt.plus(cancelGrace);
    watchdog.schedule(watchdogTask, deadline);
  }

  private long resolveEffectiveTimeoutSeconds(PulledTask task) {
    Integer fromConfig = task.getTimeoutSeconds();
    long base = (fromConfig == null || fromConfig <= 0)
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
    executionContext.put(PipelineRuntimeKeys.PAYLOAD, payload);
    executionContext.put(PipelineRuntimeKeys.TASK_ID, task.getTaskId());
    executionContext.put("workerId", task.getWorkerId());
    executionContext.put(
        PipelineRuntimeKeys.JOB_CODE,
        EmptyChecks.isNull(task.getJobCode()) ? task.getTaskType() : task.getJobCode());
    executionContext.put("businessKey", task.getBusinessKey() == null ? "" : task.getBusinessKey());
    if (task.getBizDate() != null) {
      executionContext.put(PipelineRuntimeKeys.BIZ_DATE, task.getBizDate().toString());
    }
    executionContext.put(
        PipelineRuntimeKeys.TRACE_ID, task.getTraceId() == null ? "" : task.getTraceId());
    executionContext.put(PipelineRuntimeKeys.JOB_INSTANCE_ID, task.getJobInstanceId());
    executionContext.put(PipelineRuntimeKeys.JOB_PARTITION_ID, task.getJobPartitionId());
    if (EmptyChecks.isNotNull(task.getPartitionInvocationId())) {
      executionContext.put(
          PipelineRuntimeKeys.PARTITION_INVOCATION_ID, task.getPartitionInvocationId());
    }
    if (EmptyChecks.isNotNull(task.getDependencyResultSnapshot())
        && EmptyChecks.isNotEmpty(task.getDependencyResultSnapshot())) {
      executionContext.put(
          PipelineRuntimeKeys.DEPENDENCY_RESULT_SNAPSHOT, task.getDependencyResultSnapshot());
    }
    executionContext.put("taskSeq", task.getTaskSeq());
    executionContext.put(
        "idempotencyKey", task.getIdempotencyKey() == null ? "" : task.getIdempotencyKey());
    String runMode = resolveRunMode(payload);
    if (runMode != null) {
      executionContext.put(PipelineRuntimeKeys.RUN_MODE, runMode);
    }
    // ADR-046 文件束:束 partition 的 task payload 携带 bundleSourceFileId(指向 ingress scanner 预建的
    // file_record)。落到 FILE_ID,基类适配器据此加载既有 file_record 并绑定 pipeline 实例、ReceiveStep
    // 复用不再新建。键带 bundle 前缀(P1-1 防御):只有束派发会写,普通任务 payload 里的泛化 sourceFileId
    // 不会误触发,此处 no-op,执行上下文逐字节不变。
    Object bundleSourceFileId = resolveBundleSourceFileId(payload);
    if (bundleSourceFileId != null) {
      executionContext.put(PipelineRuntimeKeys.FILE_ID, bundleSourceFileId);
      executionContext.put(PipelineRuntimeKeys.BUNDLE_SOURCE_FILE_ID, bundleSourceFileId);
    }
    if (task.getHighWaterMarkIn() != null) {
      // INCREMENTAL pipeline 业务读 attributes 拼 SQL 水位条件;FULL/CDC/历史首跑为 null。
      executionContext.put(PipelineRuntimeKeys.HIGH_WATER_MARK_IN, task.getHighWaterMarkIn());
    }
    // V94: data_interval 半开区间, 业务可拼时间窗 SQL. null 时不放, 业务侧用 bizDate 回退.
    if (task.getDataIntervalStart() != null) {
      executionContext.put(PipelineRuntimeKeys.DATA_INTERVAL_START, task.getDataIntervalStart());
    }
    if (task.getDataIntervalEnd() != null) {
      executionContext.put(PipelineRuntimeKeys.DATA_INTERVAL_END, task.getDataIntervalEnd());
    }
    // partition 信息透传:worker step / plugin 据此 + PARTITION_COUNT 决定切哪部分。null 时按"不分片"处理(下游
    // ParseStep 等会回退为 1/1)。
    if (task.getPartitionNo() != null) {
      executionContext.put(PipelineRuntimeKeys.PARTITION_NO, task.getPartitionNo());
    }
    if (task.getPartitionCount() != null) {
      executionContext.put(PipelineRuntimeKeys.PARTITION_COUNT, task.getPartitionCount());
    }
    if (task.getPartitionKey() != null) {
      executionContext.put(PipelineRuntimeKeys.PARTITION_KEY, task.getPartitionKey());
    }
    if (task.getPartitionPlanVersion() != null) {
      executionContext.put(
          PipelineRuntimeKeys.PARTITION_PLAN_VERSION, task.getPartitionPlanVersion());
    }
    if (task.getShardIndex() != null) {
      executionContext.put(PipelineRuntimeKeys.SHARD_INDEX, task.getShardIndex());
    }
    if (task.getShardTotal() != null) {
      executionContext.put(PipelineRuntimeKeys.SHARD_TOTAL, task.getShardTotal());
    }
    if (task.getRangeStartInclusive() != null) {
      executionContext.put(
          PipelineRuntimeKeys.RANGE_START_INCLUSIVE, task.getRangeStartInclusive());
    }
    if (task.getRangeEndExclusive() != null) {
      executionContext.put(PipelineRuntimeKeys.RANGE_END_EXCLUSIVE, task.getRangeEndExclusive());
    }
    if (task.getExpectedRows() != null) {
      executionContext.put(PipelineRuntimeKeys.EXPECTED_ROWS, task.getExpectedRows());
    }
    return executionContext;
  }

  private TaskExecutionReport buildReport(
      PulledTask task,
      StepExecutionResponse response,
      Map<String, Object> executionContext,
      boolean success) {
    TaskExecutionReport report = new TaskExecutionReport();
    Long reportTaskId = Long.valueOf(task.getTaskId());
    report.setTaskId(reportTaskId);
    report.setTenantId(task.getTenantId());
    report.setWorkerId(task.getWorkerId());
    report.setSuccess(success);
    report.setCode(response.code());
    report.setMessage(response.message());
    if (!success) {
      report.setErrorCode(response.code());
      report.setErrorMessage(response.message());
      String errorCode = response.code();
      String failureClass = resolveFailureClass(errorCode);
      report.setFailureClass(failureClass);
      // i18n 跨进程透传:plugin 用 StepExecutionResponse.failure(BizException, mapper)
      // 时,key/args 会一直传到 orchestrator 持久化。第三方异常 / literal 失败时为 null,orchestrator 仅用 message。
      report.setErrorKey(response.errorKey());
      report.setErrorArgs(response.errorArgs());
    }
    // 将 traceId 传递给 Orchestrator,确保状态更新与重试/DLQ 全链路可追踪
    report.setTraceId(task.getTraceId());
    report.setPartitionInvocationId(task.getPartitionInvocationId());
    String summaryCode = Objects.requireNonNullElse(response.code(), "");
    String summaryMessage = Objects.requireNonNullElse(response.message(), "");
    Map<String, String> responseSummary = Map.of(
        "code", summaryCode,
        "message", summaryMessage);
    String resultSummary = JsonUtils.toJson(responseSummary);
    report.setResultSummary(resultSummary);
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
      // ADR-030 §C: PipelineVerifierHook 把失败结果写到 attributes.VERIFIER_FAILURES；
      // 这里透传给 orchestrator，由其在处理成功上报的同一事务里写入 outbox_event 供告警面板订阅。
      Object failures = executionContext.get(PipelineRuntimeKeys.VERIFIER_FAILURES);
      if (failures instanceof List<?> failureList && !failureList.isEmpty()) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> typedFailures = (List<Map<String, Object>>) failureList;
        report.setVerifierFailures(typedFailures);
      }
    }
    return report;
  }

  private String resolveFailureClass(String errorCode) {
    if (errorCode == null || errorCode.isBlank()) {
      return null;
    }
    return FAILURE_CLASS_BY_CODE.get(errorCode);
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
      SwallowedExceptionLogger.warn(
          DefaultTaskExecutionWrapper.class, "catch:RuntimeException", ignored);

      // payload 非合法 JSON 时不设置 run_mode。
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private Object resolveBundleSourceFileId(String payload) {
    if (payload == null || payload.isBlank()) {
      return null;
    }
    try {
      Object payloadObject = JsonUtils.fromJson(payload, Object.class);
      if (payloadObject instanceof Map<?, ?> payloadMap) {
        return ((Map<String, Object>) payloadMap).get(PipelineRuntimeKeys.BUNDLE_SOURCE_FILE_ID);
      }
    } catch (RuntimeException ignored) {
      SwallowedExceptionLogger.warn(
          DefaultTaskExecutionWrapper.class, "catch:RuntimeException", ignored);

      // payload 非合法 JSON 时不注入 bundleSourceFileId。
    }
    return null;
  }
}
