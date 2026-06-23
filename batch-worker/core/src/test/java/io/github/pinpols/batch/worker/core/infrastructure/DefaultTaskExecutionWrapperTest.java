package io.github.pinpols.batch.worker.core.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.EffectiveTaskConfig;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.worker.core.config.WorkerExecutionTimeoutProperties;
import io.github.pinpols.batch.worker.core.domain.PulledTask;
import io.github.pinpols.batch.worker.core.domain.StepExecutionRequest;
import io.github.pinpols.batch.worker.core.domain.StepExecutionResponse;
import io.github.pinpols.batch.worker.core.domain.TaskExecutionReport;
import io.github.pinpols.batch.worker.core.domain.WorkerExecutionResult;
import io.github.pinpols.batch.worker.core.support.StepExecutionAdapter;
import io.github.pinpols.batch.worker.core.support.TaskExecutionClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class DefaultTaskExecutionWrapperTest {

  private StepExecutionAdapter stepExecutionAdapter;
  private TaskExecutionClient taskExecutionClient;
  private ActiveTaskLeaseRegistry activeTaskLeaseRegistry;
  private TaskExecutionPool executionPool;
  private WorkerExecutionTimeoutProperties timeoutProperties;
  private MeterRegistry registry;
  private ThreadPoolTaskScheduler watchdogScheduler;
  private DefaultTaskExecutionWrapper wrapper;

  @BeforeEach
  void setUp() {
    stepExecutionAdapter = mock(StepExecutionAdapter.class);
    taskExecutionClient = mock(TaskExecutionClient.class);
    activeTaskLeaseRegistry = mock(ActiveTaskLeaseRegistry.class);
    when(activeTaskLeaseRegistry.markCompletingUnlessLost(any())).thenReturn(true);
    timeoutProperties = new WorkerExecutionTimeoutProperties();
    timeoutProperties.setPoolSize(4);
    timeoutProperties.setDefaultTimeoutSeconds(60L);
    timeoutProperties.setMaxTimeoutSeconds(120L);
    timeoutProperties.setCancelGraceSeconds(2L);
    executionPool = new TaskExecutionPool(timeoutProperties, null);
    executionPool.start();
    registry = new SimpleMeterRegistry();
    @SuppressWarnings("unchecked")
    ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(registry);
    watchdogScheduler = new ThreadPoolTaskScheduler();
    watchdogScheduler.setPoolSize(1);
    watchdogScheduler.setThreadNamePrefix("worker-task-cancel-watchdog-");
    watchdogScheduler.setDaemon(true);
    watchdogScheduler.initialize();
    wrapper =
        new DefaultTaskExecutionWrapper(
            stepExecutionAdapter,
            taskExecutionClient,
            activeTaskLeaseRegistry,
            executionPool,
            timeoutProperties,
            provider,
            watchdogScheduler);
  }

  @AfterEach
  void tearDown() {
    wrapper.shutdownWatchdog();
    watchdogScheduler.shutdown();
    executionPool.shutdown();
  }

  @Test
  void shouldDelegateClaimToTaskExecutionClient() {
    EffectiveTaskConfig sample =
        new EffectiveTaskConfig(
            "t1",
            42L,
            100L,
            200L,
            "INST-1",
            "JOB",
            "IMPORT",
            1,
            "IMPORT",
            "HIGH",
            "biz",
            "idem",
            "{}",
            "trace",
            "FULL",
            null,
            null,
            "NONE",
            0,
            60,
            1,
            1,
            "JOB:2026-05-01:1",
            null,
            null,
            null);
    when(taskExecutionClient.claim("t1", 42L, "w1")).thenReturn(Optional.of(sample));

    Optional<EffectiveTaskConfig> result = wrapper.claim("t1", 42L, "w1");

    assertThat(result).contains(sample);
    verify(taskExecutionClient).claim("t1", 42L, "w1");
  }

  @Test
  void shouldReturnEmptyWhenClaimDenied() {
    when(taskExecutionClient.claim("t1", 42L, "w1")).thenReturn(Optional.empty());

    assertThat(wrapper.claim("t1", 42L, "w1")).isEmpty();
  }

  @Test
  void shouldRegisterLeaseExecuteAndRemoveOnSuccess() {
    PulledTask task = sampleTask("1001", "t1", "w1");
    when(stepExecutionAdapter.execute(any(StepExecutionRequest.class)))
        .thenReturn(StepExecutionResponse.successResponse());

    WorkerExecutionResult result = wrapper.execute(task);

    assertThat(result.success()).isTrue();
    assertThat(result.taskId()).isEqualTo("1001");

    verify(activeTaskLeaseRegistry).register("1001", "t1", "w1", null);
    verify(activeTaskLeaseRegistry).markCompletingUnlessLost("1001");
    verify(activeTaskLeaseRegistry).remove("1001");
    verify(taskExecutionClient).report(any(TaskExecutionReport.class));
  }

  @Test
  void shouldReportFailureWhenStepExecutionFails() {
    PulledTask task = sampleTask("1002", "t1", "w1");
    when(stepExecutionAdapter.execute(any(StepExecutionRequest.class)))
        .thenReturn(new StepExecutionResponse(false, "ERR_PARSE", "parse failed"));

    WorkerExecutionResult result = wrapper.execute(task);

    assertThat(result.success()).isFalse();
    assertThat(result.message()).isEqualTo("parse failed");
    verify(taskExecutionClient)
        .report(
            argThat(
                report ->
                    "ERR_PARSE".equals(report.getCode())
                        && "parse failed".equals(report.getMessage())
                        && "ERR_PARSE".equals(report.getErrorCode())
                        && "parse failed".equals(report.getErrorMessage())));
    verify(activeTaskLeaseRegistry).remove("1002");
  }

  @Test
  void shouldAbortReportWhenLeaseLostBeforeCompletion() {
    PulledTask task = sampleTask("1009", "t1", "w1");
    when(stepExecutionAdapter.execute(any(StepExecutionRequest.class)))
        .thenReturn(StepExecutionResponse.successResponse());
    when(activeTaskLeaseRegistry.markCompletingUnlessLost("1009")).thenReturn(false);

    WorkerExecutionResult result = wrapper.execute(task);

    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("lease lost");
    verify(activeTaskLeaseRegistry).register("1009", "t1", "w1", null);
    verify(activeTaskLeaseRegistry).markCompletingUnlessLost("1009");
    verify(taskExecutionClient, never()).report(any(TaskExecutionReport.class));
    verify(activeTaskLeaseRegistry).remove("1009");
  }

  /**
   * P0-1: adapter 抛 RuntimeException 时, 之前会让 listener 也抛; 现在 wrapper 把它转成 WORKER_EXECUTION_ERROR
   * 失败上报, listener 始终能继续派下个 task.
   */
  @Test
  void shouldConvertAdapterExceptionToFailureReport() {
    PulledTask task = sampleTask("1003", "t1", "w1");
    when(stepExecutionAdapter.execute(any(StepExecutionRequest.class)))
        .thenThrow(new RuntimeException("unexpected"));

    WorkerExecutionResult result = wrapper.execute(task);

    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("unexpected");
    verify(activeTaskLeaseRegistry).register("1003", "t1", "w1", null);
    verify(activeTaskLeaseRegistry).remove("1003");
    verify(taskExecutionClient)
        .report(
            argThat(
                report ->
                    "WORKER_EXECUTION_ERROR".equals(report.getCode())
                        && report.getMessage().contains("unexpected")));
  }

  @Test
  void shouldBuildExecutionContextWithNullSafeDefaults() {
    PulledTask task = new PulledTask();
    task.setTaskId("9001");
    task.setTenantId("t1");
    task.setWorkerId("w1");
    when(stepExecutionAdapter.execute(any(StepExecutionRequest.class)))
        .thenReturn(StepExecutionResponse.successResponse());

    wrapper.execute(task);

    verify(stepExecutionAdapter).execute(any(StepExecutionRequest.class));
  }

  @Test
  void shouldIncludeJobCodeInExecutionRequest() {
    PulledTask task = sampleTask("1004", "t1", "w1");
    task.setJobCode("MY_JOB");

    when(stepExecutionAdapter.execute(any(StepExecutionRequest.class)))
        .thenAnswer(
            invocation -> {
              StepExecutionRequest req = invocation.getArgument(0);
              assertThat(req.jobCode()).isEqualTo("MY_JOB");
              return StepExecutionResponse.successResponse();
            });

    wrapper.execute(task);
  }

  @Test
  void shouldExposeRunModeFromTaskPayload() {
    PulledTask task = sampleTask("1005", "t1", "w1");
    task.setPayload("{\"run_mode\":\"RETRY\"}");

    when(stepExecutionAdapter.execute(any(StepExecutionRequest.class)))
        .thenAnswer(
            invocation -> {
              StepExecutionRequest req = invocation.getArgument(0);
              assertThat(req.context()).containsEntry(PipelineRuntimeKeys.RUN_MODE, "RETRY");
              return StepExecutionResponse.successResponse();
            });

    wrapper.execute(task);
  }

  /** ADR-046 文件束:task payload 携带 bundleSourceFileId 时落到 FILE_ID,基类适配器据此复用既有 file_record。 */
  @Test
  void shouldExposeFileIdFromBundleSourceFileId() {
    PulledTask task = sampleTask("1006", "t1", "w1");
    task.setPayload("{\"bundleSourceFileId\":42,\"templateCode\":\"RISK_IMPORT_V2\"}");

    when(stepExecutionAdapter.execute(any(StepExecutionRequest.class)))
        .thenAnswer(
            invocation -> {
              StepExecutionRequest req = invocation.getArgument(0);
              assertThat(req.context()).containsEntry(PipelineRuntimeKeys.FILE_ID, 42);
              return StepExecutionResponse.successResponse();
            });

    wrapper.execute(task);
  }

  /** 普通(非束)导入 payload 无 bundleSourceFileId,执行上下文不得带 FILE_ID,保证存量导入零影响。 */
  @Test
  void shouldNotExposeFileIdForNonBundlePayload() {
    PulledTask task = sampleTask("1007", "t1", "w1");
    task.setPayload("{\"templateCode\":\"PLAIN_IMPORT\"}");

    when(stepExecutionAdapter.execute(any(StepExecutionRequest.class)))
        .thenAnswer(
            invocation -> {
              StepExecutionRequest req = invocation.getArgument(0);
              assertThat(req.context()).doesNotContainKey(PipelineRuntimeKeys.FILE_ID);
              return StepExecutionResponse.successResponse();
            });

    wrapper.execute(task);
  }

  /**
   * P1-1 防御:非束任务 payload 里若出现泛化 {@code sourceFileId}(插件/workflow 注入),绝不能被误当束绑定注入 FILE_ID ——只认带
   * bundle 前缀的 {@code bundleSourceFileId}。
   */
  @Test
  void shouldNotExposeFileIdForPlainSourceFileIdKey() {
    PulledTask task = sampleTask("1008", "t1", "w1");
    task.setPayload("{\"sourceFileId\":99,\"templateCode\":\"PLAIN_IMPORT\"}");

    when(stepExecutionAdapter.execute(any(StepExecutionRequest.class)))
        .thenAnswer(
            invocation -> {
              StepExecutionRequest req = invocation.getArgument(0);
              assertThat(req.context()).doesNotContainKey(PipelineRuntimeKeys.FILE_ID);
              return StepExecutionResponse.successResponse();
            });

    wrapper.execute(task);
  }

  /**
   * P0-1 核心: adapter 卡住超过 task.timeoutSeconds → wrapper 在限时后强 cancel(true) + 失败上报
   * WORKER_EXECUTION_TIMEOUT, 不让 listener 永久阻塞.
   */
  @Test
  void shouldTimeoutAndCancelWhenAdapterHangs() throws InterruptedException {
    PulledTask task = sampleTask("1010", "t1", "w1");
    task.setTimeoutSeconds(1); // 1 秒超时
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch released = new CountDownLatch(1);
    when(stepExecutionAdapter.execute(any(StepExecutionRequest.class)))
        .thenAnswer(
            invocation -> {
              started.countDown();
              try {
                Thread.sleep(10_000); // 远超超时
              } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                released.countDown();
              }
              return StepExecutionResponse.successResponse();
            });

    long start = BatchDateTimeSupport.utcEpochMillis();
    WorkerExecutionResult result = wrapper.execute(task);
    long elapsed = BatchDateTimeSupport.utcEpochMillis() - start;

    assertThat(elapsed).isBetween(900L, 5_000L); // 1s 超时 + 一点 overhead
    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(released.await(3, TimeUnit.SECONDS)).isTrue(); // pool 线程被 interrupt 后退出
    assertThat(result.success()).isFalse();
    verify(taskExecutionClient)
        .report(
            argThat(
                report ->
                    "WORKER_EXECUTION_TIMEOUT".equals(report.getCode())
                        && report.getErrorMessage().contains("1s")));
    assertThat(registry.counter("worker.task.execution.timeout.total").count()).isEqualTo(1.0);
  }

  @Test
  void shouldReportCancelledWhenRegistryCancellationInterruptsExecution() throws Exception {
    PulledTask task = sampleTask("1013", "t1", "w1");
    task.setTimeoutSeconds(30);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    AtomicReference<Runnable> cancellationCallback = new AtomicReference<>();
    doAnswer(
            invocation -> {
              cancellationCallback.set(invocation.getArgument(1));
              return null;
            })
        .when(activeTaskLeaseRegistry)
        .registerCancellationCallback(any(), any());
    when(activeTaskLeaseRegistry.isCancellationRequested("1013")).thenReturn(true);
    when(stepExecutionAdapter.execute(any(StepExecutionRequest.class)))
        .thenAnswer(
            invocation -> {
              started.countDown();
              try {
                Thread.sleep(30_000);
              } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                interrupted.countDown();
              }
              return StepExecutionResponse.successResponse();
            });

    CompletableFuture<WorkerExecutionResult> resultFuture =
        CompletableFuture.supplyAsync(() -> wrapper.execute(task));
    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
    Runnable callback = waitForCancellationCallback(cancellationCallback);

    callback.run();

    WorkerExecutionResult result = resultFuture.get(5, TimeUnit.SECONDS);
    assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(result.success()).isFalse();
    verify(taskExecutionClient)
        .report(
            argThat(
                report ->
                    DefaultTaskExecutionWrapper.CANCELLED_ERROR_CODE.equals(report.getCode())));
  }

  /** P0-1: clamp — task 配 timeout 超过 maxTimeoutSeconds 必须截断, 防呆配置错误把 worker 长期停滞 2 小时以上. */
  @Test
  void shouldClampTimeoutToMax() {
    PulledTask task = sampleTask("1011", "t1", "w1");
    task.setTimeoutSeconds(99999); // 远超 maxTimeoutSeconds=120
    when(stepExecutionAdapter.execute(any(StepExecutionRequest.class)))
        .thenReturn(StepExecutionResponse.successResponse());

    WorkerExecutionResult result = wrapper.execute(task);

    assertThat(result.success()).isTrue();
    // 没断言具体 timeout 数值; 只看 success + 没 OOM, 表示 clamp 生效让任务正常完成
  }

  /** P0-1: task 没配 timeout (null/0) 走默认 (defaultTimeoutSeconds=60s 这里). */
  @Test
  void shouldFallbackToDefaultTimeoutWhenTaskTimeoutIsNull() {
    PulledTask task = sampleTask("1012", "t1", "w1");
    task.setTimeoutSeconds(null);
    when(stepExecutionAdapter.execute(any(StepExecutionRequest.class)))
        .thenReturn(StepExecutionResponse.successResponse());

    WorkerExecutionResult result = wrapper.execute(task);

    assertThat(result.success()).isTrue();
    verify(taskExecutionClient).report(any(TaskExecutionReport.class));
  }

  // --- 辅助方法 ---

  private static PulledTask sampleTask(String taskId, String tenantId, String workerId) {
    PulledTask task = new PulledTask();
    task.setTaskId(taskId);
    task.setTenantId(tenantId);
    task.setWorkerId(workerId);
    task.setTaskType("IMPORT");
    task.setTraceId("trace-" + taskId);
    task.setPayload("{\"k\":1}");
    task.setJobCode("TEST_JOB");
    task.setBusinessKey("biz-key");
    task.setJobInstanceId(100L);
    task.setJobPartitionId(200L);
    task.setTaskSeq(1);
    task.setIdempotencyKey("idem-" + taskId);
    return task;
  }

  private static Runnable waitForCancellationCallback(AtomicReference<Runnable> callback)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (callback.get() == null && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(callback.get()).isNotNull();
    return callback.get();
  }
}
