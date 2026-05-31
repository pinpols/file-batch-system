package com.example.batch.sdk.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.sdk.client.BatchPlatformClientConfig;
import com.example.batch.sdk.internal.PlatformHttpClient;
import com.example.batch.sdk.internal.PlatformHttpException;
import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskHandler;
import com.example.batch.sdk.task.SdkTaskResult;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TaskDispatcherTest {

  private final BatchPlatformClientConfig config =
      BatchPlatformClientConfig.builder()
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

  // ─── 正常路径 ────────────────────────────────────────────────────────────────

  @Test
  void claimSuccessRunsHandlerAndReports() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    AtomicReference<SdkTaskContext> seenCtx = new AtomicReference<>();
    SdkTaskHandler handler =
        new SdkTaskHandler() {
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
    ArgumentCaptor<Map<String, Object>> reportBody = ArgumentCaptor.forClass(Map.class);
    verify(http).report(eq(42L), anyString(), reportBody.capture());
    assertThat(reportBody.getValue())
        .containsEntry("success", true)
        .containsEntry("message", "done");
    assertThat((Map<String, Object>) reportBody.getValue().get("outputs"))
        .containsEntry("rows", 100);
    assertThat(reportBody.getValue())
        .containsEntry("taskId", 42L)
        .containsEntry("tenantId", "tx")
        .containsEntry("workerId", "w-1");
  }

  // ─── handler 异常兜底 ────────────────────────────────────────────────────────

  @Test
  void handlerExceptionStillReportsFailure() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    SdkTaskHandler handler =
        new SdkTaskHandler() {
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

    ArgumentCaptor<Map<String, Object>> reportBody = ArgumentCaptor.forClass(Map.class);
    verify(http).report(eq(42L), anyString(), reportBody.capture());
    assertThat(reportBody.getValue())
        .containsEntry("success", false)
        .containsEntry("message", "biz boom")
        .containsEntry("errorCode", "RuntimeException")
        .containsEntry("resultSummary", "biz boom");
  }

  @Test
  void handlerReturnsNullTreatedAsFailure() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    SdkTaskHandler handler =
        new SdkTaskHandler() {
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

    ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
    verify(http).report(eq(42L), anyString(), body.capture());
    assertThat(body.getValue())
        .containsEntry("success", false)
        .containsEntry("message", "handler returned null");
  }

  // ─── 未注册 taskType ────────────────────────────────────────────────────────

  @Test
  void unknownTaskTypeReportsFailureWithoutClaim() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    dispatcher = new TaskDispatcher(config, Map.of("tt", noopHandler()), http);

    dispatcher.processInWorkerThread(msg("unknown_type"));

    verify(http, never()).claim(anyLong(), anyString(), any());
    ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
    verify(http).report(eq(42L), anyString(), body.capture());
    assertThat((String) body.getValue().get("message"))
        .contains("no handler registered for taskType=unknown_type");
  }

  // ─── CLAIM 失败 ─────────────────────────────────────────────────────────────

  @Test
  void claimFailureDoesNotExecuteOrReport() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.claim(anyLong(), anyString(), any()))
        .thenThrow(new PlatformHttpException(409, "409 already claimed"));
    AtomicBoolean executed = new AtomicBoolean();
    SdkTaskHandler handler =
        new SdkTaskHandler() {
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

  // ─── REPORT 失败:不再 retry,等 orchestrator lease 超时 ──────────────────────

  @Test
  void reportFailureSwallowed() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.report(anyLong(), anyString(), any())).thenThrow(new IOException("503 down"));
    dispatcher = new TaskDispatcher(config, Map.of("tt", noopHandler()), http);

    // 不应抛
    dispatcher.processInWorkerThread(msg("tt"));

    verify(http).report(eq(42L), anyString(), any());
  }

  // ─── 异步路径 onMessage 集成测试 ───────────────────────────────────────────────

  @Test
  void onMessageRunsAsyncOnExecutor() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    CountDownLatch executed = new CountDownLatch(1);
    SdkTaskHandler handler =
        new SdkTaskHandler() {
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

  // ─── invalid message 跳过 ────────────────────────────────────────────────────

  @Test
  void invalidMessageSkippedWithoutSubmit() {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    dispatcher = new TaskDispatcher(config, Map.of(), http);

    TaskDispatchMessage bad =
        new TaskDispatchMessage(null, "tx", "j", "t", "ti", Map.of(), Map.of());
    dispatcher.onMessage(bad); // 不抛
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
    return org.mockito.ArgumentMatchers.eq(v);
  }
}
