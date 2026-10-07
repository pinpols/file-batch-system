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
import io.github.pinpols.batch.worker.core.config.WorkerConcurrencyProperties;
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
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@DisplayName("任务执行包装器: 租约登记, 执行上下文装配, 超时取消与结果上报")
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
    executionPool = new TaskExecutionPool(timeoutProperties, concurrencyProperties(4));
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
    wrapper = new DefaultTaskExecutionWrapper(
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
  @DisplayName("领取请求直接委托给任务执行客户端并返回任务配置")
  void shouldDelegateClaimToTaskExecutionClient() {
    EffectiveTaskConfig sample = new EffectiveTaskConfig(
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
        null,
        null,
        null,
        null,
        null,
        null,
        null);
    when(taskExecutionClient.claim("t1", 42L, "w1")).thenReturn(Optional.of(sample));

    Optional<EffectiveTaskConfig> result = wrapper.claim("t1", 42L, "w1");

    assertThat(result).contains(sample);
    verify(taskExecutionClient).claim("t1", 42L, "w1");
  }

  @Test
  @DisplayName("领取被拒绝时返回空结果")
  void shouldReturnEmptyWhenClaimDenied() {
    when(taskExecutionClient.claim("t1", 42L, "w1")).thenReturn(Optional.empty());

    assertThat(wrapper.claim("t1", 42L, "w1")).isEmpty();
  }

  @Test
  @DisplayName("执行成功后登记租约, 标记完成中, 结束时移除租约并上报结果")
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
  @DisplayName("步骤执行失败时上报失败码与错误信息, 并移除租约")
  void shouldReportFailureWhenStepExecutionFails() {
    PulledTask task = sampleTask("1002", "t1", "w1");
    when(stepExecutionAdapter.execute(any(StepExecutionRequest.class)))
        .thenReturn(new StepExecutionResponse(false, "ERR_PARSE", "parse failed"));

    WorkerExecutionResult result = wrapper.execute(task);

    assertThat(result.success()).isFalse();
    assertThat(result.message()).isEqualTo("parse failed");
    verify(taskExecutionClient)
        .report(argThat(report -> "ERR_PARSE".equals(report.getCode())
            && "parse failed".equals(report.getMessage())
            && "ERR_PARSE".equals(report.getErrorCode())
            && "parse failed".equals(report.getErrorMessage())));
    verify(activeTaskLeaseRegistry).remove("1002");
  }

  /**
   * 回归:第三方插件返回的 StepExecutionResponse 允许 message / code 为 null(record 无 @NotNull, 字面失败构造器 {@code
   * new StepExecutionResponse(false, code, message)} 契约就允许)。 resultSummary 曾用 Map.of("code", code,
   * "message", message) 构造 → null 时 NPE,把干净的失败 上报掩盖成执行崩溃。见 DefaultTaskExecutionWrapper 的
   * resultSummary 构造。
   */
  @Test
  @DisplayName("失败响应缺少错误描述时仍正常上报, 不因空值抛异常")
  void shouldReportFailureWhenResponseMessageNull_withoutNpe() {
    PulledTask task = sampleTask("1014", "t1", "w1");
    when(stepExecutionAdapter.execute(any(StepExecutionRequest.class)))
        .thenReturn(new StepExecutionResponse(false, "ERR_PARSE", null));

    WorkerExecutionResult result = wrapper.execute(task);

    assertThat(result.success()).isFalse();
    verify(taskExecutionClient).report(argThat(report -> "ERR_PARSE".equals(report.getCode())));
    verify(activeTaskLeaseRegistry).remove("1014");
  }

  @Test
  @DisplayName("完成前租约已丢失时中止上报, 返回租约丢失失败并清理登记")
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
  @DisplayName("适配器抛出运行时异常时转换为执行错误上报, 租约正常清理")
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
        .report(argThat(report -> "WORKER_EXECUTION_ERROR".equals(report.getCode())
            && report.getMessage().contains("unexpected")));
  }

  @Test
  @DisplayName("任务字段大多为空时仍能构造执行请求并进入适配器")
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
  @DisplayName("执行请求携带任务上的作业编码")
  void shouldIncludeJobCodeInExecutionRequest() {
    PulledTask task = sampleTask("1004", "t1", "w1");
    task.setJobCode("MY_JOB");

    when(stepExecutionAdapter.execute(any(StepExecutionRequest.class))).thenAnswer(invocation -> {
      StepExecutionRequest req = invocation.getArgument(0);
      assertThat(req.jobCode()).isEqualTo("MY_JOB");
      return StepExecutionResponse.successResponse();
    });

    wrapper.execute(task);
  }

  @Test
  @DisplayName("任务载荷中的运行模式透传到执行上下文")
  void shouldExposeRunModeFromTaskPayload() {
    PulledTask task = sampleTask("1005", "t1", "w1");
    task.setPayload("{\"run_mode\":\"RETRY\"}");

    when(stepExecutionAdapter.execute(any(StepExecutionRequest.class))).thenAnswer(invocation -> {
      StepExecutionRequest req = invocation.getArgument(0);
      assertThat(req.context()).containsEntry(PipelineRuntimeKeys.RUN_MODE, "RETRY");
      return StepExecutionResponse.successResponse();
    });

    wrapper.execute(task);
  }

  /** ADR-046 文件束:task payload 携带 bundleSourceFileId 时落到 FILE_ID,基类适配器据此复用既有 file_record。 */
  @Test
  @DisplayName("文件束载荷中的来源文件标识透传为执行上下文中的文件标识")
  void shouldExposeFileIdFromBundleSourceFileId() {
    PulledTask task = sampleTask("1006", "t1", "w1");
    task.setPayload("{\"bundleSourceFileId\":42,\"templateCode\":\"RISK_IMPORT_V2\"}");

    when(stepExecutionAdapter.execute(any(StepExecutionRequest.class))).thenAnswer(invocation -> {
      StepExecutionRequest req = invocation.getArgument(0);
      assertThat(req.context()).containsEntry(PipelineRuntimeKeys.FILE_ID, 42);
      return StepExecutionResponse.successResponse();
    });

    wrapper.execute(task);
  }

  /** 普通(非束)导入 payload 无 bundleSourceFileId,执行上下文不得带 FILE_ID,保证存量导入零影响。 */
  @Test
  @DisplayName("普通导入载荷不含文件束标识时, 执行上下文不带文件标识")
  void shouldNotExposeFileIdForNonBundlePayload() {
    PulledTask task = sampleTask("1007", "t1", "w1");
    task.setPayload("{\"templateCode\":\"PLAIN_IMPORT\"}");

    when(stepExecutionAdapter.execute(any(StepExecutionRequest.class))).thenAnswer(invocation -> {
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
  @DisplayName("仅带通用来源文件字段的载荷不被当作文件束绑定, 执行上下文不带文件标识")
  void shouldNotExposeFileIdForPlainSourceFileIdKey() {
    PulledTask task = sampleTask("1008", "t1", "w1");
    task.setPayload("{\"sourceFileId\":99,\"templateCode\":\"PLAIN_IMPORT\"}");

    when(stepExecutionAdapter.execute(any(StepExecutionRequest.class))).thenAnswer(invocation -> {
      StepExecutionRequest req = invocation.getArgument(0);
      assertThat(req.context()).doesNotContainKey(PipelineRuntimeKeys.FILE_ID);
      return StepExecutionResponse.successResponse();
    });

    wrapper.execute(task);
  }

  @Test
  @DisplayName("分区计划版本, 分片序号与总量, 区间边界与预期行数全部透传到执行上下文")
  void shouldExposePartitionPlanContractInExecutionContext() {
    PulledTask task = sampleTask("1013", "t1", "w1");
    task.setPartitionPlanVersion(1);
    task.setShardIndex(2);
    task.setShardTotal(4);
    task.setRangeStartInclusive(500L);
    task.setRangeEndExclusive(750L);
    task.setExpectedRows(250L);

    when(stepExecutionAdapter.execute(any(StepExecutionRequest.class))).thenAnswer(invocation -> {
      StepExecutionRequest req = invocation.getArgument(0);
      assertThat(req.context())
          .containsEntry(PipelineRuntimeKeys.PARTITION_PLAN_VERSION, 1)
          .containsEntry(PipelineRuntimeKeys.SHARD_INDEX, 2)
          .containsEntry(PipelineRuntimeKeys.SHARD_TOTAL, 4)
          .containsEntry(PipelineRuntimeKeys.RANGE_START_INCLUSIVE, 500L)
          .containsEntry(PipelineRuntimeKeys.RANGE_END_EXCLUSIVE, 750L)
          .containsEntry(PipelineRuntimeKeys.EXPECTED_ROWS, 250L);
      return StepExecutionResponse.successResponse();
    });

    wrapper.execute(task);
  }

  /**
   * P0-1 核心: adapter 卡住超过 task.timeoutSeconds → wrapper 在限时后强 cancel(true) + 失败上报
   * WORKER_EXECUTION_TIMEOUT, 不让 listener 永久阻塞.
   */
  @Test
  @DisplayName("适配器超时未返回时按限时中断执行并上报超时失败, 同时累加超时计数")
  void shouldTimeoutAndCancelWhenAdapterHangs() throws InterruptedException {
    PulledTask task = sampleTask("1010", "t1", "w1");
    task.setTimeoutSeconds(1); // 1 秒超时
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch released = new CountDownLatch(1);
    when(stepExecutionAdapter.execute(any(StepExecutionRequest.class))).thenAnswer(invocation -> {
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
        .report(argThat(report -> "WORKER_EXECUTION_TIMEOUT".equals(report.getCode())
            && report.getErrorMessage().contains("1s")));
    assertThat(registry.counter("worker.task.execution.timeout.total").count()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("任务不响应取消时看门狗检测到线程未退出, 累加线程泄漏计数")
  void shouldDetectLeakedExecution_whenTaskIgnoresCancellation() throws Exception {
    var scheduler = mock(TaskScheduler.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(registry);
    var tested = new DefaultTaskExecutionWrapper(
        stepExecutionAdapter,
        taskExecutionClient,
        activeTaskLeaseRegistry,
        executionPool,
        timeoutProperties,
        provider,
        scheduler);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch exited = new CountDownLatch(1);
    when(stepExecutionAdapter.execute(any())).thenAnswer(invocation -> {
      try {
        while (release.getCount() > 0) {
          try {
            release.await();
          } catch (InterruptedException ignored) {
            // 故意模拟不遵守协作式中断的插件，测试结束时由 release 释放。
          }
        }
        return StepExecutionResponse.successResponse();
      } finally {
        exited.countDown();
      }
    });
    PulledTask task = sampleTask("1014", "t1", "w1");
    task.setTimeoutSeconds(1);
    try {
      assertThat(tested.execute(task).success()).isFalse();
      ArgumentCaptor<Runnable> check = ArgumentCaptor.forClass(Runnable.class);
      verify(scheduler).schedule(check.capture(), any(Instant.class));
      assertThat(exited.getCount()).isEqualTo(1);
      check.getValue().run();
      assertThat(registry.counter("worker.task.execution.thread.leaked.total").count())
          .isEqualTo(1);
    } finally {
      release.countDown();
      assertThat(exited.await(3, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName("注册表请求取消并已中断执行时上报取消失败码")
  void shouldReportCancelledWhenRegistryCancellationInterruptsExecution() throws Exception {
    PulledTask task = sampleTask("1013", "t1", "w1");
    task.setTimeoutSeconds(30);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    AtomicReference<Runnable> cancellationCallback = new AtomicReference<>();
    doAnswer(invocation -> {
          cancellationCallback.set(invocation.getArgument(1));
          return null;
        })
        .when(activeTaskLeaseRegistry)
        .registerCancellationCallback(any(), any());
    when(activeTaskLeaseRegistry.isCancellationRequested("1013")).thenReturn(true);
    when(stepExecutionAdapter.execute(any(StepExecutionRequest.class))).thenAnswer(invocation -> {
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
        .report(argThat(
            report -> DefaultTaskExecutionWrapper.CANCELLED_ERROR_CODE.equals(report.getCode())));
  }

  /** P0-1: clamp — task 配 timeout 超过 maxTimeoutSeconds 必须截断, 防呆配置错误把 worker 长期停滞 2 小时以上. */
  @Test
  @DisplayName("任务配置的超时超过上限时被截断, 任务仍能正常完成")
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
  @DisplayName("任务未配置超时时回退到默认超时并正常上报")
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

  private static WorkerConcurrencyProperties concurrencyProperties(int maxConcurrentTasks) {
    WorkerConcurrencyProperties properties = new WorkerConcurrencyProperties();
    properties.setMaxConcurrentTasks(maxConcurrentTasks);
    return properties;
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
