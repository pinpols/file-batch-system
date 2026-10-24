package io.github.pinpols.batch.sdk.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.sdk.client.BatchPlatformClientConfig;
import io.github.pinpols.batch.sdk.internal.PlatformHttpClient;
import io.github.pinpols.batch.sdk.internal.PlatformHttpException;
import io.github.pinpols.batch.sdk.task.SdkDependencyResultReference;
import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import io.github.pinpols.batch.sdk.task.SdkTaskHandler;
import io.github.pinpols.batch.sdk.task.SdkTaskResult;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

@DisplayName("TaskDispatcher 主链路 — 认领执行回报、异常回退与异步派发")
class TaskDispatcherTest {

  private final BatchPlatformClientConfig config = BatchPlatformClientConfig.builder()
      .baseUrl("http://localhost:0")
      .tenantId("tx")
      .workerCode("w-1")
      .kafkaBootstrap("kafka:9092")
      .kafkaTopicPattern("batch.task.dispatch.tx.*")
      .kafkaGroupId("g")
      .maxConcurrentTasks(2)
      .build();

  private TaskDispatcher dispatcher;

  @AfterEach
  void tearDown() {
    if (dispatcher != null) dispatcher.stop();
  }

  private TaskDispatchMessage msg(String taskType) {
    return new TaskDispatchMessage(
        42L, "tx", "job-1", taskType, "ti-9", Map.of("p", 1), Map.of("traceId", "abc"));
  }

  private void stubCurrentClaim(PlatformHttpClient http, long taskId, String invocationId)
      throws IOException {
    PlatformHttpClient.TaskClaimResponse response =
        new PlatformHttpClient.TaskClaimResponse(invocationId);
    when(http.claim(eq(taskId), anyString(), any())).thenReturn(response);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
    return ArgumentCaptor.forClass((Class) Map.class);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> objectMap(Object value) {
    return (Map<String, Object>) value;
  }

  // ─── 正常路径 ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("认领成功后执行处理器并回报成功结果,上下文与回报字段完整")
  void shouldRunHandlerAndReport_whenClaimSucceeds() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.claim(eq(42L), anyString(), any()))
        .thenReturn(new PlatformHttpClient.TaskClaimResponse("inv-42", "{\"p\":1}", List.of()));
    AtomicReference<SdkTaskContext> seenCtx = new AtomicReference<>();
    SdkTaskHandler handler = new SdkTaskHandler() {
      @Override
      public String taskType() {
        return "tt";
      }

      @Override
      public SdkTaskResult execute(SdkTaskContext ctx) {
        seenCtx.set(ctx);
        return SdkTaskResult.ok("done", Map.of("rows", 100));
      }
    };
    dispatcher = new TaskDispatcher(config, Map.of("tt", handler), http);

    dispatcher.processInWorkerThread(msg("tt"));

    assertThat(seenCtx.get()).isNotNull();
    assertThat(seenCtx.get().tenantId()).isEqualTo("tx");
    assertThat(seenCtx.get().taskId()).isEqualTo(42L);
    assertThat(seenCtx.get().parameters()).containsEntry("p", 1);

    verify(http).claim(eq(42L), anyString(), any());
    ArgumentCaptor<Map<String, Object>> reportBody = mapCaptor();
    verify(http).report(eq(42L), anyString(), reportBody.capture());
    assertThat(reportBody.getValue())
        .containsEntry("success", true)
        .containsEntry("message", "done");
    assertThat(objectMap(reportBody.getValue().get("outputs"))).containsEntry("rows", 100);
    assertThat(reportBody.getValue())
        .containsEntry("taskId", 42L)
        .containsEntry("tenantId", "tx")
        .containsEntry("workerId", "w-1");
  }

  @Test
  @DisplayName("分区调用标识透传到认领与回报报文,任务结束后映射被清理")
  void shouldThreadPartitionInvocation_toClaimAndReport() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    stubCurrentClaim(http, 42L, "inv-77");
    SdkTaskHandler handler = new SdkTaskHandler() {
      @Override
      public String taskType() {
        return "tt";
      }

      @Override
      public SdkTaskResult execute(SdkTaskContext ctx) {
        return SdkTaskResult.ok("done", Map.of());
      }
    };
    dispatcher = new TaskDispatcher(config, Map.of("tt", handler), http);

    // 分区任务:runtimeAttributes 带 partitionInvocationId(平台 R3-P1-10/R3-P0-5 强制不变量)。
    TaskDispatchMessage partitioned = new TaskDispatchMessage(
        42L,
        "tx",
        "job-1",
        "tt",
        "ti-9",
        Map.of("p", 1),
        Map.of("traceId", "abc", "partitionInvocationId", "inv-77"));
    dispatcher.processInWorkerThread(partitioned);

    ArgumentCaptor<Map<String, Object>> claimBody = mapCaptor();
    verify(http).claim(eq(42L), anyString(), claimBody.capture());
    assertThat(claimBody.getValue()).containsEntry("partitionInvocationId", "inv-77");

    ArgumentCaptor<Map<String, Object>> reportBody = mapCaptor();
    verify(http).report(eq(42L), anyString(), reportBody.capture());
    assertThat(reportBody.getValue()).containsEntry("partitionInvocationId", "inv-77");

    // task 结束后映射已清(finally remove),不泄漏。
    assertThat(dispatcher.partitionInvocation(42L)).isNull();
  }

  @Test
  @DisplayName("认领响应返回的分区调用标识透传到回报,任务结束后不残留")
  void shouldThreadClaimResponseInvocation_toReport() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.claim(eq(42L), anyString(), any()))
        .thenReturn(new PlatformHttpClient.TaskClaimResponse("inv-from-claim"));
    SdkTaskHandler handler = new SdkTaskHandler() {
      @Override
      public String taskType() {
        return "tt";
      }

      @Override
      public SdkTaskResult execute(SdkTaskContext ctx) {
        return SdkTaskResult.ok("done", Map.of());
      }
    };
    dispatcher = new TaskDispatcher(config, Map.of("tt", handler), http);

    dispatcher.processInWorkerThread(
        new TaskDispatchMessage(42L, "tx", "job-1", "tt", "ti-9", Map.of(), Map.of()));

    ArgumentCaptor<Map<String, Object>> reportBody = mapCaptor();
    verify(http).report(eq(42L), anyString(), reportBody.capture());
    assertThat(reportBody.getValue()).containsEntry("partitionInvocationId", "inv-from-claim");
    assertThat(dispatcher.partitionInvocation(42L)).isNull();
  }

  @Test
  @DisplayName("依赖结果引用从 CLAIM 回包透传给自托管 Worker handler")
  void shouldExposeDependencyResultSnapshotFromClaimResponse() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    SdkDependencyResultReference reference = new SdkDependencyResultReference(
        "UPSTREAM_JOB",
        "PREVIOUS_BIZ_DAY",
        LocalDate.parse("2026-04-30"),
        "job:UPSTREAM_JOB:2026-04-30",
        4,
        9001L,
        8001L,
        Instant.parse("2026-05-01T00:00:00Z"));
    when(http.claim(eq(42L), anyString(), any()))
        .thenReturn(new PlatformHttpClient.TaskClaimResponse("inv-1", List.of(reference)));
    AtomicReference<SdkTaskContext> observed = new AtomicReference<>();
    SdkTaskHandler handler = new SdkTaskHandler() {
      @Override
      public String taskType() {
        return "tt";
      }

      @Override
      public SdkTaskResult execute(SdkTaskContext ctx) {
        observed.set(ctx);
        return SdkTaskResult.ok("done", Map.of());
      }
    };
    dispatcher = new TaskDispatcher(config, Map.of("tt", handler), http);

    dispatcher.processInWorkerThread(
        new TaskDispatchMessage(42L, "tx", "job-1", "tt", "ti-9", Map.of(), Map.of()));

    assertThat(observed.get().dependencyResultSnapshot()).containsExactly(reference);
  }

  @Test
  @DisplayName("handler 使用 CLAIM 冻结参数，不使用派发消息中的旧参数")
  void shouldUseClaimPayloadAsHandlerParameters() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    String pinnedPayload =
        "{\"crossDay\":{\"prior_day\":{\"resultVersionId\":9876,\"output\":{\"total\":42}}}}";
    when(http.claim(eq(42L), anyString(), any()))
        .thenReturn(new PlatformHttpClient.TaskClaimResponse("inv-1", pinnedPayload, List.of()));
    AtomicReference<SdkTaskContext> observed = new AtomicReference<>();
    SdkTaskHandler handler = new SdkTaskHandler() {
      @Override
      public String taskType() {
        return "tt";
      }

      @Override
      public SdkTaskResult execute(SdkTaskContext ctx) {
        observed.set(ctx);
        return SdkTaskResult.ok("done", Map.of());
      }
    };
    dispatcher = new TaskDispatcher(config, Map.of("tt", handler), http);

    dispatcher.processInWorkerThread(new TaskDispatchMessage(
        "v2",
        42L,
        "tx",
        "job-1",
        "tt",
        "ti-9",
        Map.of("crossDay", Map.of("prior_day", Map.of("resultVersionId", 111L))),
        Map.of()));

    assertThat(observed.get().parameters())
        .containsEntry(
            "crossDay",
            Map.of("prior_day", Map.of("resultVersionId", 9876, "output", Map.of("total", 42))));
  }

  @Test
  @DisplayName("CLAIM 参数不是 JSON 对象时 handler 不执行并回报失败")
  void shouldFailTaskWhenClaimPayloadIsNotObjectJson() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.claim(eq(42L), anyString(), any()))
        .thenReturn(new PlatformHttpClient.TaskClaimResponse("inv-1", "[1,2]", List.of()));
    SdkTaskHandler handler = new SdkTaskHandler() {
      @Override
      public String taskType() {
        return "tt";
      }

      @Override
      public SdkTaskResult execute(SdkTaskContext ctx) {
        throw new AssertionError("handler must not run with malformed task parameters");
      }
    };
    dispatcher = new TaskDispatcher(config, Map.of("tt", handler), http);

    dispatcher.processInWorkerThread(msg("tt"));

    ArgumentCaptor<Map<String, Object>> report = mapCaptor();
    verify(http).report(eq(42L), anyString(), report.capture());
    assertThat(report.getValue()).containsEntry("success", false);
    assertThat(report.getValue()).containsKey("errorCode");
  }

  // ─── handler 异常回退 ────────────────────────────────────────────────────────

  @Test
  @DisplayName("处理器抛出异常时仍回报失败,统一错误码且摘要保留异常细节")
  void shouldReportFailure_whenHandlerThrows() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    stubCurrentClaim(http, 42L, "inv-42");
    SdkTaskHandler handler = new SdkTaskHandler() {
      @Override
      public String taskType() {
        return "tt";
      }

      @Override
      public SdkTaskResult execute(SdkTaskContext ctx) {
        throw new RuntimeException("biz boom");
      }
    };
    dispatcher = new TaskDispatcher(config, Map.of("tt", handler), http);

    dispatcher.processInWorkerThread(msg("tt"));

    ArgumentCaptor<Map<String, Object>> reportBody = mapCaptor();
    verify(http).report(eq(42L), anyString(), reportBody.capture());
    // #P2 errorCode 词表统一:未捕获 handler 异常 → 统一 protocol 常量 EXECUTION_FAILED
    // (不再是异常类 SimpleName "RuntimeException");原异常类名保留在 resultSummary.message 里可诊断。
    assertThat(reportBody.getValue())
        .containsEntry("success", false)
        .containsEntry("message", "biz boom") // 顶层 message 仍是业务原因(不变)
        .containsEntry("errorCode", "EXECUTION_FAILED");
    // result_summary 是 JSONB:发 {code,message} 合法 JSON 对象,不是普通文本(否则平台解析 500)
    assertThat(reportBody.getValue().get("resultSummary").toString())
        .contains("\"code\":\"EXECUTION_FAILED\"")
        .contains("biz boom")
        .contains("RuntimeException"); // 类名折进 resultSummary.message 维持可诊断性
  }

  @Test
  @DisplayName("处理器返回空结果按失败回报,并给出明确原因")
  void shouldTreatAsFailure_whenHandlerReturnsNull() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    stubCurrentClaim(http, 42L, "inv-42");
    SdkTaskHandler handler = new SdkTaskHandler() {
      @Override
      public String taskType() {
        return "tt";
      }

      @Override
      public SdkTaskResult execute(SdkTaskContext ctx) {
        return null;
      }
    };
    dispatcher = new TaskDispatcher(config, Map.of("tt", handler), http);

    dispatcher.processInWorkerThread(msg("tt"));

    ArgumentCaptor<Map<String, Object>> body = mapCaptor();
    verify(http).report(eq(42L), anyString(), body.capture());
    assertThat(body.getValue())
        .containsEntry("success", false)
        .containsEntry("message", "handler returned null");
  }

  // ─── 未注册 taskType ────────────────────────────────────────────────────────

  @Test
  @DisplayName("任务类型未注册时不认领,直接回报失败并说明原因")
  void shouldReportFailureWithoutClaim_whenTaskTypeUnregistered() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    dispatcher = new TaskDispatcher(config, Map.of("tt", noopHandler()), http);

    dispatcher.processInWorkerThread(msg("unknown_type"));

    verify(http, never()).claim(anyLong(), anyString(), any());
    ArgumentCaptor<Map<String, Object>> body = mapCaptor();
    verify(http).report(eq(42L), anyString(), body.capture());
    assertThat(String.valueOf(body.getValue().get("message")))
        .contains("no handler registered for taskType=unknown_type");
  }

  // ─── CLAIM 失败 ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("认领失败时不执行处理器也不回报,避免污染任务状态")
  void shouldNotExecuteOrReport_whenClaimFails() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.claim(anyLong(), anyString(), any()))
        .thenThrow(new PlatformHttpException(409, "409 already claimed"));
    AtomicBoolean executed = new AtomicBoolean();
    SdkTaskHandler handler = new SdkTaskHandler() {
      @Override
      public String taskType() {
        return "tt";
      }

      @Override
      public SdkTaskResult execute(SdkTaskContext ctx) {
        executed.set(true);
        return SdkTaskResult.ok();
      }
    };
    dispatcher = new TaskDispatcher(config, Map.of("tt", handler), http);

    dispatcher.processInWorkerThread(msg("tt"));

    assertThat(executed).isFalse();
    verify(http, never()).report(anyLong(), anyString(), any());
  }

  // ─── REPORT 传输失败:5xx/IO 退避重试,耗尽后捕获并抑制,等 orchestrator lease 超时 ──────────

  @Test
  @DisplayName("回报传输失败时抑制异常不外抛,仅尝试一次等待租约超时兜底")
  void shouldSwallowReportFailure_andReportOnce() throws Exception {
    // 关掉 report 重试(0 次)让本用例只验"吞异常不外抛 + 调一次"语义,避免退避 sleep 拖慢测试。
    BatchPlatformClientConfig noRetryConfig =
        config.toBuilder().claimMax5xxRetries(0).build();
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    stubCurrentClaim(http, 42L, "inv-42");
    doThrow(new IOException("503 down")).when(http).report(anyLong(), anyString(), any());
    dispatcher = new TaskDispatcher(noRetryConfig, Map.of("tt", noopHandler()), http);

    // 不应抛
    dispatcher.processInWorkerThread(msg("tt"));

    verify(http).report(eq(42L), anyString(), any());
  }

  // ─── 异步路径 onMessage 集成测试 ───────────────────────────────────────────────

  @Test
  @DisplayName("已接纳的工作项晚于前一 Error 才进入执行器时，不得再次认领或执行同一任务")
  void shouldNotExecutePreviouslySubmittedTask_whenFatalPrecedesWorkerEntry() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    stubCurrentClaim(http, 42L, "inv-42");
    AssertionError fatal = new AssertionError("execution thread terminated after effect");
    AtomicInteger effects = new AtomicInteger();
    SdkTaskHandler handler = new SdkTaskHandler() {
      @Override
      public String taskType() {
        return "tt";
      }

      @Override
      public SdkTaskResult execute(SdkTaskContext ctx) {
        int execution = effects.incrementAndGet();
        if (execution == 1) throw fatal;
        return SdkTaskResult.ok("late duplicate executed");
      }
    };
    dispatcher = new TaskDispatcher(config, Map.of("tt", handler), http);
    TaskDispatchMessage message = msg("tt");

    Throwable observed = catchThrowable(() -> dispatcher.processInWorkerThread(message));
    assertThat(observed).isSameAs(fatal);
    assertThat(dispatcher.isFatal()).isTrue();
    assertThat(dispatcher.inFlightTaskIds()).isEmpty();
    assertThat(dispatcher.partitionInvocation(42L)).isNull();
    // 包可见入口就是 executor 已接纳的工作项；它可能在 onMessage 的早期检查后才被调度。
    dispatcher.processInWorkerThread(message);

    assertThat(effects.get()).isEqualTo(1);
    verify(http, times(1)).claim(eq(42L), anyString(), any());
    verify(http, never()).report(anyLong(), anyString(), any());
    assertThat(dispatcher.inFlightTaskIds()).isEmpty();
    assertThat(dispatcher.partitionInvocation(42L)).isNull();
  }

  @Test
  @DisplayName("消息派发在线程池异步执行,处理器被真正调用")
  void shouldRunAsync_whenMessageDispatched() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    stubCurrentClaim(http, 42L, "inv-42");
    CountDownLatch executed = new CountDownLatch(1);
    SdkTaskHandler handler = new SdkTaskHandler() {
      @Override
      public String taskType() {
        return "tt";
      }

      @Override
      public SdkTaskResult execute(SdkTaskContext ctx) {
        executed.countDown();
        return SdkTaskResult.ok();
      }
    };
    dispatcher = new TaskDispatcher(config, Map.of("tt", handler), http);

    dispatcher.onMessage(msg("tt"));

    assertThat(executed.await(2, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  @DisplayName("同owner认领均可成功的重复消息只执行一次,重复调用不得移除仍执行任务的续租上下文")
  void shouldExecuteOnlyOnce_whenSameOwnerReceivesConcurrentDuplicate() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    PlatformHttpClient.TaskClaimResponse claim =
        new PlatformHttpClient.TaskClaimResponse("inv-42", "{}", List.of());
    when(http.claim(eq(42L), anyString(), any())).thenReturn(claim);
    AtomicInteger executions = new AtomicInteger();
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    SdkTaskHandler handler = new SdkTaskHandler() {
      @Override
      public String taskType() {
        return "tt";
      }

      @Override
      public SdkTaskResult execute(SdkTaskContext context) {
        if (executions.incrementAndGet() == 1) {
          firstEntered.countDown();
          try {
            if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
              throw new IllegalStateException("first handler was not released");
            }
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
          }
        }
        return SdkTaskResult.ok();
      }
    };
    dispatcher = new TaskDispatcher(config, Map.of("tt", handler), http);
    TaskDispatchMessage message = msg("tt");
    ExecutorService callers = Executors.newFixedThreadPool(2);
    Future<?> first = callers.submit(() -> dispatcher.processInWorkerThread(message));
    try {
      boolean firstStarted = firstEntered.await(2, TimeUnit.SECONDS);
      assertThat(firstStarted).isTrue();
      Future<?> duplicate = callers.submit(() -> dispatcher.processInWorkerThread(message));
      duplicate.get(2, TimeUnit.SECONDS);

      assertThat(executions.get()).isEqualTo(1);
      assertThat(dispatcher.inFlightTaskIds()).containsExactly(42L);
      assertThat(dispatcher.partitionInvocation(42L)).isEqualTo("inv-42");
    } finally {
      releaseFirst.countDown();
      try {
        first.get(2, TimeUnit.SECONDS);
      } finally {
        callers.shutdownNow();
        callers.awaitTermination(2, TimeUnit.SECONDS);
      }
    }
    verify(http, times(1)).report(eq(42L), anyString(), any());
    assertThat(dispatcher.inFlightTaskIds()).isEmpty();
  }

  // ─── invalid message 跳过 ────────────────────────────────────────────────────

  @Test
  @DisplayName("非法消息直接终态丢弃,不占用并发许可也不认领")
  void shouldDropTerminally_whenMessageInvalid() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    dispatcher = new TaskDispatcher(config, Map.of(), http);

    TaskDispatchMessage bad =
        new TaskDispatchMessage(null, "tx", "j", "t", "ti", Map.of(), Map.of());

    assertThat(dispatcher.onMessage(bad)).isEqualTo(TaskDispatcher.DispatchDecision.DROP_TERMINAL);
    assertThat(dispatcher.inFlightCount()).isZero();
    verify(http, never()).claim(anyLong(), anyString(), any());
  }

  // ─── helpers ───────────────────────────────────────────────────────────────

  private static SdkTaskHandler noopHandler() {
    return new SdkTaskHandler() {
      @Override
      public String taskType() {
        return "tt";
      }

      @Override
      public SdkTaskResult execute(SdkTaskContext ctx) {
        return SdkTaskResult.ok();
      }
    };
  }

  private static <T> T eq(T v) {
    return ArgumentMatchers.eq(v);
  }
}
