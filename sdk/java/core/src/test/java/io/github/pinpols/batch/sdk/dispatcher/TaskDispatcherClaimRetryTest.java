package io.github.pinpols.batch.sdk.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
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
import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import io.github.pinpols.batch.sdk.task.SdkTaskHandler;
import io.github.pinpols.batch.sdk.task.SdkTaskResult;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 1 #SDK-P1-2 — CLAIM 401/403 fail-fast + 5xx 指数退避 + 409 peer / 其它 4xx give up。
 *
 * <p>测试 base delay 设小到 1ms,跑得快;exponential 行为见 {@link #shouldRetryUpToConfiguredCount_whenClaimUnavailable}。
 */
@DisplayName("TaskDispatcher 认领重试 — 鉴权快速失败、冲突让位与 5xx 退避重试")
class TaskDispatcherClaimRetryTest {

  private final BatchPlatformClientConfig config = BatchPlatformClientConfig.builder()
      .baseUrl("http://localhost:0")
      .tenantId("tx")
      .workerCode("w-1")
      .kafkaBootstrap("k:9092")
      .kafkaTopicPattern("p.*")
      .kafkaGroupId("g")
      .maxConcurrentTasks(2)
      .claimMax5xxRetries(3)
      .claimRetryBaseDelay(Duration.ofMillis(1)) // 跑测试不真等 200/400/800ms
      .build();

  private TaskDispatcher dispatcher;

  @AfterEach
  void tearDown() {
    if (dispatcher != null) dispatcher.stop();
  }

  private TaskDispatchMessage msg() {
    return new TaskDispatchMessage(
        42L, "tx", "job-1", "tt", "ti-9", Map.of("p", 1), Map.of("traceId", "tr-x"));
  }

  // ─── 401 / 403 → fail-fast,标记 fatal,不重试,不 report ─────────────────────────

  @Test
  @DisplayName("认领返回未授权时标记致命且严格不重试,也不回报任务状态")
  void shouldMarkFatalWithoutRetry_whenClaimUnauthorized() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.claim(anyLong(), anyString(), any()))
        .thenThrow(new PlatformHttpException(401, "Unauthorized"));
    SdkTaskHandler handler = trackedHandler(new AtomicBoolean());
    dispatcher = new TaskDispatcher(config, Map.of("tt", handler), http);

    dispatcher.processInWorkerThread(msg());

    assertThat(dispatcher.isFatal()).isTrue();
    verify(http, times(1)).claim(anyLong(), anyString(), any()); // 严格不重试
    verify(http, never()).report(anyLong(), anyString(), any()); // 不污染 task 状态
  }

  @Test
  @DisplayName("认领返回禁止访问时标记致命并放弃本次派发")
  void shouldMarkFatalWithoutRetry_whenClaimForbidden() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.claim(anyLong(), anyString(), any()))
        .thenThrow(new PlatformHttpException(403, "Forbidden"));
    dispatcher = new TaskDispatcher(config, Map.of("tt", noopHandler()), http);

    dispatcher.processInWorkerThread(msg());

    assertThat(dispatcher.isFatal()).isTrue();
    verify(http, times(1)).claim(anyLong(), anyString(), any());
    verify(http, never()).report(anyLong(), anyString(), any());
  }

  @Test
  @DisplayName("进入致命状态后新消息不再派发,也不产生额外认领调用")
  void shouldDropNewMessages_whenDispatcherFatal() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.claim(anyLong(), anyString(), any()))
        .thenThrow(new PlatformHttpException(401, "Unauthorized"));
    AtomicBoolean executed = new AtomicBoolean();
    dispatcher = new TaskDispatcher(config, Map.of("tt", trackedHandler(executed)), http);

    // 第一条触发 fatal
    dispatcher.processInWorkerThread(msg());
    assertThat(dispatcher.isFatal()).isTrue();

    // 第二条应被 onMessage drop(不进 executor)
    dispatcher.onMessage(msg());

    // 给线程池一点时间,确认确实没再被派单
    Thread.sleep(50);
    assertThat(executed).isFalse();
    // claim 仍只调过 1 次(第一条;onMessage drop 后不进 processCore)
    verify(http, times(1)).claim(anyLong(), anyString(), any());
  }

  // ─── 409 → peer 已 claim,放弃,不 report,不重试 ─────────────────────────────────

  @Test
  @DisplayName("认领冲突视为同伴已持有,静默放弃且不回报不重试")
  void shouldSkipSilently_whenClaimConflict() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.claim(anyLong(), anyString(), any()))
        .thenThrow(new PlatformHttpException(409, "already claimed by peer"));
    AtomicBoolean executed = new AtomicBoolean();
    dispatcher = new TaskDispatcher(config, Map.of("tt", trackedHandler(executed)), http);

    dispatcher.processInWorkerThread(msg());

    assertThat(executed).isFalse();
    assertThat(dispatcher.isFatal()).isFalse(); // 不 fatal
    verify(http, times(1)).claim(anyLong(), anyString(), any());
    verify(http, never()).report(anyLong(), anyString(), any());
  }

  // ─── 其它 4xx(400 / 404 / 422)→ 客户端构造错误,放弃,不重试 ───────────────────────

  @Test
  @DisplayName("认领目标不存在时不重试也不回报,且不标记致命")
  void shouldSkipWithoutRetry_whenClaimNotFound() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.claim(anyLong(), anyString(), any()))
        .thenThrow(new PlatformHttpException(404, "task gone"));
    dispatcher = new TaskDispatcher(config, Map.of("tt", noopHandler()), http);

    dispatcher.processInWorkerThread(msg());

    assertThat(dispatcher.isFatal()).isFalse();
    verify(http, times(1)).claim(anyLong(), anyString(), any());
    verify(http, never()).report(anyLong(), anyString(), any());
  }

  // ─── 5xx → 指数退避重试,直到耗尽或成功 ─────────────────────────────────────────

  @Test
  @DisplayName("服务不可用时按配置次数重试,耗尽后放弃且不回报")
  void shouldRetryUpToConfiguredCount_whenClaimUnavailable() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.claim(anyLong(), anyString(), any()))
        .thenThrow(new PlatformHttpException(503, "Service Unavailable"));
    dispatcher = new TaskDispatcher(config, Map.of("tt", noopHandler()), http);

    dispatcher.processInWorkerThread(msg());

    // 首试 1 + 3 重试 = 4 次
    verify(http, times(4)).claim(anyLong(), anyString(), any());
    assertThat(dispatcher.isFatal()).isFalse();
    verify(http, never()).report(anyLong(), anyString(), any());
  }

  @Test
  @DisplayName("重试后认领成功则继续执行处理器并回报结果")
  void shouldRunHandlerAndReport_whenClaimSucceedsAfterRetry() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    AtomicInteger calls = new AtomicInteger();
    when(http.claim(anyLong(), anyString(), any())).thenAnswer(inv -> {
      if (calls.incrementAndGet() <= 2) {
        throw new PlatformHttpException(502, "Bad Gateway");
      }
      return new PlatformHttpClient.TaskClaimResponse("ti-9");
    });
    AtomicBoolean executed = new AtomicBoolean();
    dispatcher = new TaskDispatcher(config, Map.of("tt", trackedHandler(executed)), http);

    dispatcher.processInWorkerThread(msg());

    assertThat(executed).isTrue();
    verify(http, times(3)).claim(anyLong(), anyString(), any()); // 2 fail + 1 ok
    verify(http, times(1)).report(anyLong(), anyString(), any()); // 成功 report
  }

  @Test
  @DisplayName("重试次数配置为零时只尝试一次,失败即放弃")
  void shouldGiveUpImmediately_whenRetryDisabled() throws Exception {
    BatchPlatformClientConfig zeroRetry =
        config.toBuilder().claimMax5xxRetries(0).build();
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.claim(anyLong(), anyString(), any()))
        .thenThrow(new PlatformHttpException(500, "ISE"));
    dispatcher = new TaskDispatcher(zeroRetry, Map.of("tt", noopHandler()), http);

    dispatcher.processInWorkerThread(msg());

    verify(http, times(1)).claim(anyLong(), anyString(), any());
    verify(http, never()).report(anyLong(), anyString(), any());
  }

  // ─── P7-2:CLAIM/REPORT 连续 4xx 达阈值 → fail-fast ──────────────────────────────

  @Test
  @DisplayName("连续客户端错误达到阈值时进入致命状态并累计错误计数")
  void shouldTripFatal_whenClientErrorsReachThreshold() throws Exception {
    BatchPlatformClientConfig threshold3 =
        config.toBuilder().clientErrorFailFastThreshold(3).build();
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.claim(anyLong(), anyString(), any()))
        .thenThrow(new PlatformHttpException(400, "bad request"));
    dispatcher = new TaskDispatcher(threshold3, Map.of("tt", noopHandler()), http);

    dispatcher.processInWorkerThread(msg());
    assertThat(dispatcher.isFatal()).isFalse(); // 1 次,未达阈值
    dispatcher.processInWorkerThread(msg());
    assertThat(dispatcher.isFatal()).isFalse(); // 2 次
    dispatcher.processInWorkerThread(msg());
    assertThat(dispatcher.isFatal()).isTrue(); // 第 3 次连续 4xx → fatal
    assertThat(dispatcher.consecutiveClientErrors()).isEqualTo(3);
  }

  @Test
  @DisplayName("认领成功后连续错误计数归零,不触发致命状态")
  void shouldResetErrorStreak_whenClaimSucceeds() throws Exception {
    BatchPlatformClientConfig threshold3 =
        config.toBuilder().clientErrorFailFastThreshold(3).build();
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    AtomicInteger calls = new AtomicInteger();
    when(http.claim(anyLong(), anyString(), any())).thenAnswer(inv -> {
      if (calls.incrementAndGet() <= 2) {
        throw new PlatformHttpException(404, "task gone");
      }
      return new PlatformHttpClient.TaskClaimResponse("ti-9");
    });
    dispatcher = new TaskDispatcher(threshold3, Map.of("tt", noopHandler()), http);

    dispatcher.processInWorkerThread(msg()); // 404 → 1
    dispatcher.processInWorkerThread(msg()); // 404 → 2
    assertThat(dispatcher.consecutiveClientErrors()).isEqualTo(2);
    dispatcher.processInWorkerThread(msg()); // 成功 claim + report → 归零

    assertThat(dispatcher.consecutiveClientErrors()).isZero();
    assertThat(dispatcher.isFatal()).isFalse();
  }

  @Test
  @DisplayName("阈值为零表示关闭快速失败,多次客户端错误也不进入致命状态")
  void shouldNeverTripFatal_whenThresholdDisabled() throws Exception {
    BatchPlatformClientConfig disabled =
        config.toBuilder().clientErrorFailFastThreshold(0).build();
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.claim(anyLong(), anyString(), any()))
        .thenThrow(new PlatformHttpException(404, "task gone"));
    dispatcher = new TaskDispatcher(disabled, Map.of("tt", noopHandler()), http);

    for (int i = 0; i < 10; i++) {
      dispatcher.processInWorkerThread(msg());
    }
    assertThat(dispatcher.isFatal()).isFalse(); // 阈值 0 = 关闭,永不触发
  }

  // ─── P7-2:REPORT 路径的非鉴权、非 409 4xx 也计入 consecutiveClientErrors ──────────

  @Test
  @DisplayName("回报阶段的非鉴权非冲突客户端错误计入连续错误计数")
  void shouldCountTowardStreak_whenReportReturnsClientError() throws Exception {
    BatchPlatformClientConfig threshold3 =
        config.toBuilder().clientErrorFailFastThreshold(3).build();
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.claim(anyLong(), anyString(), any()))
        .thenReturn(new PlatformHttpClient.TaskClaimResponse(null)); // CLAIM 成功
    doThrow(new PlatformHttpException(422, "unprocessable"))
        .when(http)
        .report(anyLong(), anyString(), any()); // REPORT 422
    dispatcher = new TaskDispatcher(threshold3, Map.of("tt", noopHandler()), http);

    dispatcher.processInWorkerThread(msg());
    // CLAIM 成功会先 resetClientErrorStreak,REPORT 422 再 +1 → 净 1
    assertThat(dispatcher.consecutiveClientErrors()).isEqualTo(1);
    assertThat(dispatcher.isFatal()).isFalse();
  }

  @Test
  @DisplayName("回报鉴权失败不计入连续错误计数,但同样标记致命")
  void shouldMarkFatalWithoutCounting_whenReportUnauthorized() throws Exception {
    BatchPlatformClientConfig threshold3 =
        config.toBuilder().clientErrorFailFastThreshold(3).build();
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.claim(anyLong(), anyString(), any()))
        .thenReturn(new PlatformHttpClient.TaskClaimResponse(null));
    doThrow(new PlatformHttpException(401, "unauthorized"))
        .when(http)
        .report(anyLong(), anyString(), any()); // 鉴权类不计入此计数器
    dispatcher = new TaskDispatcher(threshold3, Map.of("tt", noopHandler()), http);

    dispatcher.processInWorkerThread(msg());

    // CLAIM 成功归零,REPORT 401 是鉴权类(!isAuthError 守卫)→ 不计入连续 4xx 计数器
    assertThat(dispatcher.consecutiveClientErrors()).isZero();
    // 但 reportWithRetry 对 401/403 同样 fail-fast(与 CLAIM 一致),标记 dispatcher fatal
    assertThat(dispatcher.isFatal()).isTrue();
  }

  @Test
  @DisplayName("回报冲突不计入连续错误计数,也不标记致命")
  void shouldNotCountTowardStreak_whenReportConflict() throws Exception {
    BatchPlatformClientConfig threshold3 =
        config.toBuilder().clientErrorFailFastThreshold(3).build();
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.claim(anyLong(), anyString(), any()))
        .thenReturn(new PlatformHttpClient.TaskClaimResponse(null));
    doThrow(new PlatformHttpException(409, "already reported"))
        .when(http)
        .report(anyLong(), anyString(), any()); // 409 不计入
    dispatcher = new TaskDispatcher(threshold3, Map.of("tt", noopHandler()), http);

    dispatcher.processInWorkerThread(msg());

    assertThat(dispatcher.consecutiveClientErrors()).isZero();
    assertThat(dispatcher.isFatal()).isFalse();
  }

  @Test
  @DisplayName("每轮认领成功都会归零,仅回报反复失败的客户端错误不会累积触发致命")
  void shouldNotAccumulate_whenOnlyReportKeepsFailing() throws Exception {
    BatchPlatformClientConfig threshold3 =
        config.toBuilder().clientErrorFailFastThreshold(3).build();
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.claim(anyLong(), anyString(), any()))
        .thenReturn(new PlatformHttpClient.TaskClaimResponse(null));
    doThrow(new PlatformHttpException(400, "bad report body"))
        .when(http)
        .report(anyLong(), anyString(), any());
    dispatcher = new TaskDispatcher(threshold3, Map.of("tt", noopHandler()), http);

    // 契约:每轮 CLAIM 成功先归零,REPORT 400 再 +1 → 净每轮维持 1,REPORT-only 4xx 不会累积触发 fatal
    dispatcher.processInWorkerThread(msg());
    dispatcher.processInWorkerThread(msg());
    dispatcher.processInWorkerThread(msg());
    assertThat(dispatcher.consecutiveClientErrors()).isEqualTo(1);
    assertThat(dispatcher.isFatal()).isFalse();
  }

  // ─── 传输错误(generic IOException)→ 当 5xx 退避重试 ────────────────────────────

  @Test
  @DisplayName("认领传输异常按服务不可用处理并退避重试")
  void shouldRetry_whenClaimTransportError() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.claim(anyLong(), anyString(), any())).thenThrow(new IOException("connection reset"));
    dispatcher = new TaskDispatcher(config, Map.of("tt", noopHandler()), http);

    dispatcher.processInWorkerThread(msg());

    verify(http, times(4)).claim(anyLong(), anyString(), any()); // 1 + 3
    assertThat(dispatcher.isFatal()).isFalse();
  }

  @Test
  @DisplayName("传输异常后重试成功即恢复执行,不进入致命状态")
  void shouldRecover_whenTransportErrorThenSuccess() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    AtomicInteger calls = new AtomicInteger();
    when(http.claim(anyLong(), anyString(), any())).thenAnswer(inv -> {
      if (calls.incrementAndGet() == 1) throw new IOException("read timeout");
      return new PlatformHttpClient.TaskClaimResponse("ti-9");
    });
    AtomicBoolean executed = new AtomicBoolean();
    dispatcher = new TaskDispatcher(config, Map.of("tt", trackedHandler(executed)), http);

    dispatcher.processInWorkerThread(msg());

    assertThat(executed).isTrue();
    verify(http, times(2)).claim(anyLong(), anyString(), any());
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

  private static SdkTaskHandler trackedHandler(AtomicBoolean executed) {
    return new SdkTaskHandler() {
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
  }
}
