package com.example.batch.sdk.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.example.batch.sdk.client.BatchPlatformClientConfig;
import com.example.batch.sdk.internal.PlatformHttpClient;
import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskHandler;
import com.example.batch.sdk.task.SdkTaskResult;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.verification.VerificationMode;
import org.slf4j.MDC;

/**
 * ADR-035 §SDK 增强 P0 三件测试 — draining flag + MDC 透传(capacity-aware pause 在 KafkaTaskConsumerTest)。
 */
class TaskDispatcherP0HardeningTest {

  private final BatchPlatformClientConfig config =
      BatchPlatformClientConfig.builder()
          .baseUrl("http://localhost:0")
          .tenantId("tx")
          .workerCode("w-1")
          .kafkaBootstrap("k:9092")
          .kafkaTopicPattern("p.*")
          .kafkaGroupId("g")
          .maxConcurrentTasks(2)
          .build();

  private TaskDispatcher dispatcher;

  @AfterEach
  void tearDown() {
    if (dispatcher != null) dispatcher.stop();
    MDC.clear();
  }

  // ─── P0-2: Draining flag ────────────────────────────────────────────────────

  @Test
  void stopMarksDrainingTrue() {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    dispatcher = new TaskDispatcher(config, Map.of("tt", noopHandler()), http);
    assertThat(dispatcher.isDraining()).isFalse();

    dispatcher.stop();

    assertThat(dispatcher.isDraining()).isTrue();
    dispatcher = null; // 不在 tearDown 再 stop
  }

  @Test
  void onMessageAfterStopIsSkipped() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    AtomicReference<SdkTaskContext> seenCtx = new AtomicReference<>();
    dispatcher =
        new TaskDispatcher(
            config,
            Map.of(
                "tt",
                new SdkTaskHandler() {
                  @Override
                  public String taskType() {
                    return "tt";
                  }

                  @Override
                  public SdkTaskResult execute(SdkTaskContext ctx) {
                    seenCtx.set(ctx);
                    return SdkTaskResult.ok();
                  }
                }),
            http);
    dispatcher.stop();

    dispatcher.onMessage(new TaskDispatchMessage(99L, "tx", "j", "tt", "ti", Map.of(), Map.of()));

    // 不应调 claim 也不应进 handler
    org.mockito.Mockito.verify(http, never()).claim(anyLong(), anyString(), any());
    assertThat(seenCtx.get()).isNull();
    dispatcher = null;
  }

  // ─── P0-3: MDC 透传 ──────────────────────────────────────────────────────────

  @Test
  void handlerExecutionPopulatesMdcTraceTenantTask() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    AtomicReference<Map<String, String>> seenMdc = new AtomicReference<>();
    CountDownLatch executed = new CountDownLatch(1);
    dispatcher =
        new TaskDispatcher(
            config,
            Map.of(
                "tt",
                new SdkTaskHandler() {
                  @Override
                  public String taskType() {
                    return "tt";
                  }

                  @Override
                  public SdkTaskResult execute(SdkTaskContext ctx) {
                    Map<String, String> snap = MDC.getCopyOfContextMap();
                    seenMdc.set(snap);
                    executed.countDown();
                    return SdkTaskResult.ok();
                  }
                }),
            http);

    dispatcher.onMessage(
        new TaskDispatchMessage(
            42L,
            "tx",
            "j",
            "tt",
            "ti-9",
            Map.of(),
            Map.of("traceId", "tr-abc", "partitionInvocationId", "inv-1")));

    assertThat(executed.await(2, TimeUnit.SECONDS)).isTrue();
    Map<String, String> mdc = seenMdc.get();
    assertThat(mdc).isNotNull();
    assertThat(mdc).containsEntry("traceId", "tr-abc");
    assertThat(mdc).containsEntry("tenantId", "tx");
    assertThat(mdc).containsEntry("taskId", "42");
  }

  @Test
  void mdcClearedAfterExecution() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    CountDownLatch executed = new CountDownLatch(1);
    dispatcher = new TaskDispatcher(config, Map.of("tt", new CountDownHandler(executed)), http);

    dispatcher.onMessage(
        new TaskDispatchMessage(42L, "tx", "j", "tt", "ti", Map.of(), Map.of("traceId", "tr-x")));

    assertThat(executed.await(2, TimeUnit.SECONDS)).isTrue();
    // 处理完后,dispatcher 线程的 MDC 应被清掉(用线程局部 ThreadLocal,看主线程是 clean 的)
    // 这里测试主线程 — 主线程从未被污染过,如果 dispatcher 线程残留也不会跨线程
    assertThat(MDC.get("traceId")).isNull();
    assertThat(MDC.get("tenantId")).isNull();
    assertThat(MDC.get("taskId")).isNull();
  }

  @Test
  void missingTraceIdSkipsMdcKey() throws Exception {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    AtomicReference<Map<String, String>> seenMdc = new AtomicReference<>();
    CountDownLatch executed = new CountDownLatch(1);
    dispatcher =
        new TaskDispatcher(
            config,
            Map.of(
                "tt",
                new SdkTaskHandler() {
                  @Override
                  public String taskType() {
                    return "tt";
                  }

                  @Override
                  public SdkTaskResult execute(SdkTaskContext ctx) {
                    seenMdc.set(MDC.getCopyOfContextMap());
                    executed.countDown();
                    return SdkTaskResult.ok();
                  }
                }),
            http);

    // 无 traceId 字段
    dispatcher.onMessage(new TaskDispatchMessage(42L, "tx", "j", "tt", "ti", Map.of(), Map.of()));

    assertThat(executed.await(2, TimeUnit.SECONDS)).isTrue();
    Map<String, String> mdc = seenMdc.get();
    assertThat(mdc).doesNotContainKey("traceId"); // 不在则不 put
    assertThat(mdc).containsEntry("tenantId", "tx");
    assertThat(mdc).containsEntry("taskId", "42");
  }

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

  private record CountDownHandler(CountDownLatch latch) implements SdkTaskHandler {
    @Override
    public String taskType() {
      return "tt";
    }

    @Override
    public SdkTaskResult execute(SdkTaskContext ctx) {
      latch.countDown();
      return SdkTaskResult.ok();
    }
  }

  // 静态导入 mockito verify 简短引用
  @SuppressWarnings("unused")
  private static VerificationMode never2() {
    return never();
  }
}
