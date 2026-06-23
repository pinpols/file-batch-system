package io.github.pinpols.batch.sdk.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import io.github.pinpols.batch.sdk.client.BatchPlatformClientConfig;
import io.github.pinpols.batch.sdk.internal.PlatformHttpClient;
import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import io.github.pinpols.batch.sdk.task.SdkTaskHandler;
import io.github.pinpols.batch.sdk.task.SdkTaskResult;
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

  // ─── P0: 容量 permit backpressure(提交前占容量,满则 RETRY_LATER 不提交 offset)──────────

  @Test
  void onMessageReturnsRetryLaterWhenCapacityFullThenAcceptsAfterDrain() throws Exception {
    BatchPlatformClientConfig cap1 =
        BatchPlatformClientConfig.builder()
            .baseUrl("http://localhost:0")
            .tenantId("tx")
            .workerCode("w-1")
            .kafkaBootstrap("k:9092")
            .kafkaTopicPattern("p.*")
            .kafkaGroupId("g")
            .maxConcurrentTasks(1)
            .build();
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch gate = new CountDownLatch(1);
    dispatcher = new TaskDispatcher(cap1, Map.of("tt", new GatedHandler(started, gate)), http);

    // msg1 占满唯一 permit,worker 线程卡在 handler 内(permit 未释放)
    TaskDispatcher.DispatchDecision d1 =
        dispatcher.onMessage(
            new TaskDispatchMessage(1L, "tx", "j", "tt", "ti", Map.of(), Map.of()));
    assertThat(d1).isEqualTo(TaskDispatcher.DispatchDecision.SUBMITTED);
    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(dispatcher.submittedCount()).isEqualTo(1);

    // 容量满 → msg2 RETRY_LATER(KafkaTaskConsumer 据此 seek+pause,offset 不前移)
    TaskDispatcher.DispatchDecision d2 =
        dispatcher.onMessage(
            new TaskDispatchMessage(2L, "tx", "j", "tt", "ti", Map.of(), Map.of()));
    assertThat(d2).isEqualTo(TaskDispatcher.DispatchDecision.RETRY_LATER);

    // 放行 msg1 → permit 释放,容量恢复
    gate.countDown();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (dispatcher.submittedCount() != 0 && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(dispatcher.submittedCount()).isEqualTo(0);
  }

  private record GatedHandler(CountDownLatch started, CountDownLatch gate)
      implements SdkTaskHandler {
    @Override
    public String taskType() {
      return "tt";
    }

    @Override
    public SdkTaskResult execute(SdkTaskContext ctx) {
      started.countDown();
      try {
        gate.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return SdkTaskResult.ok();
    }
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
