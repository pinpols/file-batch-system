package com.example.batch.sdk.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.sdk.client.BatchPlatformClientConfig;
import com.example.batch.sdk.internal.PlatformHttpClient;
import com.example.batch.sdk.internal.PlatformHttpException;
import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskHandler;
import com.example.batch.sdk.task.SdkTaskResult;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Phase 1 #SDK-P1-2 — CLAIM 401/403 fail-fast + 5xx 指数退避 + 409 peer / 其它 4xx give up。
 *
 * <p>测试 base delay 设小到 1ms,跑得快;exponential 行为见 {@link #claim503RetriesUpToConfiguredCount}。
 */
class TaskDispatcherClaimRetryTest {

  private final BatchPlatformClientConfig config =
      BatchPlatformClientConfig.builder()
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
  void claim401MarksDispatcherFatalAndSkipsRetry() throws Exception {
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
  void claim403MarksDispatcherFatalAndSkipsRetry() throws Exception {
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
  void fatalDispatcherDropsNewMessages() throws Exception {
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
  void claim409SilentlySkipsWithoutReportOrRetry() throws Exception {
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
  void claim404SkipsWithoutRetry() throws Exception {
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
  void claim503RetriesUpToConfiguredCount() throws Exception {
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
  void claim5xxThenSuccessRunsHandlerAndReports() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    AtomicInteger calls = new AtomicInteger();
    when(http.claim(anyLong(), anyString(), any()))
        .thenAnswer(
            inv -> {
              if (calls.incrementAndGet() <= 2) {
                throw new PlatformHttpException(502, "Bad Gateway");
              }
              return Map.of();
            });
    AtomicBoolean executed = new AtomicBoolean();
    dispatcher = new TaskDispatcher(config, Map.of("tt", trackedHandler(executed)), http);

    dispatcher.processInWorkerThread(msg());

    assertThat(executed).isTrue();
    verify(http, times(3)).claim(anyLong(), anyString(), any()); // 2 fail + 1 ok
    verify(http, times(1)).report(anyLong(), anyString(), any()); // 成功 report
  }

  @Test
  void claim5xxWithZeroRetriesGivesUpImmediately() throws Exception {
    BatchPlatformClientConfig zeroRetry = config.toBuilder().claimMax5xxRetries(0).build();
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
  void consecutiveClientErrorsTripFatalAtThreshold() throws Exception {
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
  void successfulClaimResetsClientErrorStreak() throws Exception {
    BatchPlatformClientConfig threshold3 =
        config.toBuilder().clientErrorFailFastThreshold(3).build();
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    AtomicInteger calls = new AtomicInteger();
    when(http.claim(anyLong(), anyString(), any()))
        .thenAnswer(
            inv -> {
              if (calls.incrementAndGet() <= 2) {
                throw new PlatformHttpException(404, "task gone");
              }
              return Map.of();
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
  void clientErrorFailFastDisabledWhenThresholdZero() throws Exception {
    BatchPlatformClientConfig disabled = config.toBuilder().clientErrorFailFastThreshold(0).build();
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.claim(anyLong(), anyString(), any()))
        .thenThrow(new PlatformHttpException(404, "task gone"));
    dispatcher = new TaskDispatcher(disabled, Map.of("tt", noopHandler()), http);

    for (int i = 0; i < 10; i++) {
      dispatcher.processInWorkerThread(msg());
    }
    assertThat(dispatcher.isFatal()).isFalse(); // 阈值 0 = 关闭,永不触发
  }

  // ─── 传输错误(generic IOException)→ 当 5xx 退避重试 ────────────────────────────

  @Test
  void claimTransportErrorRetries() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.claim(anyLong(), anyString(), any())).thenThrow(new IOException("connection reset"));
    dispatcher = new TaskDispatcher(config, Map.of("tt", noopHandler()), http);

    dispatcher.processInWorkerThread(msg());

    verify(http, times(4)).claim(anyLong(), anyString(), any()); // 1 + 3
    assertThat(dispatcher.isFatal()).isFalse();
  }

  @Test
  void claimTransportErrorThenSuccessRecovers() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    AtomicInteger calls = new AtomicInteger();
    when(http.claim(anyLong(), anyString(), any()))
        .thenAnswer(
            inv -> {
              if (calls.incrementAndGet() == 1) throw new IOException("read timeout");
              return Map.of();
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
