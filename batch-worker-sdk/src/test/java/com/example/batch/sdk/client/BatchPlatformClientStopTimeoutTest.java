package com.example.batch.sdk.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.batch.sdk.dispatcher.KafkaTaskConsumer;
import com.example.batch.sdk.dispatcher.TaskDispatcher;
import com.example.batch.sdk.internal.PlatformHttpClient;
import com.example.batch.sdk.scheduler.HeartbeatScheduler;
import com.example.batch.sdk.scheduler.LeaseRenewalScheduler;
import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskHandler;
import com.example.batch.sdk.task.SdkTaskResult;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * P0 hardening — {@link BatchPlatformClient#stop(Duration)} 必须在给定超时内返回(不死等), dispatcher drain
 * 超时未结束的 in-flight task id 必须出现在 WARN 日志里(运维可见 SIGKILL 前哪些任务被打断)。
 */
class BatchPlatformClientStopTimeoutTest {

  private final ExecutorService busyPool = Executors.newSingleThreadExecutor();
  private ListAppender<ILoggingEvent> dispatcherAppender;
  private Logger dispatcherLogger;

  private static BatchPlatformClientConfig cfg() {
    return BatchPlatformClientConfig.builder()
        .baseUrl("https://batch.example.com")
        .tenantId("tx")
        .workerCode("w-1")
        .kafkaBootstrap("kafka:9092")
        .kafkaTopicPattern("batch.task.dispatch.tx.*")
        .kafkaGroupId("g")
        .maxConcurrentTasks(2)
        .build();
  }

  private static void inject(Object target, String fieldName, Object value) throws Exception {
    Field f = target.getClass().getDeclaredField(fieldName);
    f.setAccessible(true);
    f.set(target, value);
  }

  @AfterEach
  void tearDown() {
    if (dispatcherLogger != null && dispatcherAppender != null) {
      dispatcherLogger.detachAppender(dispatcherAppender);
    }
    busyPool.shutdownNow();
  }

  /** dispatcher.stop(timeout) 直接验证:跑一个 1s 的慢 task,200ms 超时应在 ~200ms 内返回 + WARN 列出未完成 task id。 */
  @Test
  void dispatcherStopReturnsWithinTimeoutAndWarnsAboutInFlightTasks() throws Exception {
    attachWarnCapture();
    SdkTaskHandler dummy =
        new SdkTaskHandler() {
          @Override
          public String taskType() {
            return "noop";
          }

          @Override
          public SdkTaskResult execute(SdkTaskContext ctx) {
            return SdkTaskResult.ok("noop");
          }
        };
    TaskDispatcher dispatcher =
        new TaskDispatcher(cfg(), Map.of("noop", dummy), mock(PlatformHttpClient.class));
    // 把一个慢 task 注入 in-flight + executor —— 模拟 handler 还没跑完
    Set<Long> inFlight = inFlightSet(dispatcher);
    inFlight.add(9001L);
    inFlight.add(9002L);
    CountDownLatch started = new CountDownLatch(1);
    AtomicBoolean interrupted = new AtomicBoolean(false);
    submitSlowTask(dispatcher, started, interrupted, 1_000);
    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

    // arrange — record stop wall clock
    long t0 = System.nanoTime();
    dispatcher.stop(Duration.ofMillis(200));
    long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

    // assert:必须很快返回(给 jitter buffer 800ms);超时 WARN 必须含 in-flight id
    assertThat(elapsedMs)
        .as("stop should return close to 200ms timeout, not wait full 1s")
        .isLessThan(900L);
    assertThat(warnMessages())
        .anySatisfy(m -> assertThat(m).contains("drain timeout").contains("9001").contains("9002"));
  }

  /** BatchPlatformClient.stop(Duration) 端到端:与 stop() 走同一顺序,且把超时透传给 dispatcher。 */
  @Test
  void clientStopWithTimeoutDelegatesToDispatcherStopWithBudget() throws Exception {
    BatchPlatformClient client = BatchPlatformClient.builder(cfg()).build();
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    KafkaTaskConsumer kafka = mock(KafkaTaskConsumer.class);
    HeartbeatScheduler hb = mock(HeartbeatScheduler.class);
    LeaseRenewalScheduler lease = mock(LeaseRenewalScheduler.class);
    Thread kafkaThread = new Thread(() -> {}, "test-kafka");
    kafkaThread.start();
    kafkaThread.join();

    inject(client, "httpClient", http);
    inject(client, "dispatcher", dispatcher);
    inject(client, "kafkaConsumer", kafka);
    inject(client, "kafkaConsumerThread", kafkaThread);
    inject(client, "heartbeatScheduler", hb);
    inject(client, "leaseRenewalScheduler", lease);
    inject(client, "started", true);

    long t0 = System.nanoTime();
    client.stop(Duration.ofMillis(500));
    long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

    // 所有 mock 在毫秒级返回,client.stop 不应卡在任何阶段
    assertThat(elapsedMs).isLessThan(1_000L);
    verify(kafka).close();
    verify(dispatcher).stop(any(Duration.class));
    verify(hb).close();
    verify(lease).close();
    verify(http).deactivate(anyString(), any());
  }

  /**
   * Round-3 #1:long timeout 优雅路径 — 慢 task 在 200ms 内完成,2s 超时应在 ~200ms 内返回,且 dispatcher.stop 不打
   * "drain timeout" WARN(in-flight 都 drain 干净了)。
   */
  @Test
  void dispatcherStopReturnsEarlyWhenInFlightDrainsBeforeTimeout() throws Exception {
    attachWarnCapture();
    SdkTaskHandler dummy =
        new SdkTaskHandler() {
          @Override
          public String taskType() {
            return "noop";
          }

          @Override
          public SdkTaskResult execute(SdkTaskContext ctx) {
            return SdkTaskResult.ok("noop");
          }
        };
    TaskDispatcher dispatcher =
        new TaskDispatcher(cfg(), Map.of("noop", dummy), mock(PlatformHttpClient.class));
    CountDownLatch started = new CountDownLatch(1);
    AtomicBoolean interrupted = new AtomicBoolean(false);
    submitSlowTask(dispatcher, started, interrupted, 200);
    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

    long t0 = System.nanoTime();
    dispatcher.stop(Duration.ofSeconds(2));
    long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

    // task 200ms 跑完即 drain 完毕,不应等满 2s
    assertThat(elapsedMs).as("stop should return shortly after task finishes").isLessThan(1_500L);
    assertThat(interrupted).as("task should finish naturally, not interrupted").isFalse();
    assertThat(warnMessages()).noneSatisfy(m -> assertThat(m).contains("drain timeout"));
  }

  /** null timeout 视为 0ms — 立刻 forceful 关,验证不 NPE。 */
  @Test
  void dispatcherStopHandlesNullTimeoutAsZero() {
    TaskDispatcher dispatcher = new TaskDispatcher(cfg(), Map.of(), mock(PlatformHttpClient.class));
    dispatcher.stop(null);
    assertThat(dispatcher.isDraining()).isTrue();
  }

  // ─── helpers ────────────────────────────────────────────────────────────

  private void attachWarnCapture() {
    dispatcherLogger = (Logger) LoggerFactory.getLogger(TaskDispatcher.class);
    dispatcherAppender = new ListAppender<>();
    dispatcherAppender.start();
    dispatcherLogger.addAppender(dispatcherAppender);
  }

  private List<String> warnMessages() {
    return dispatcherAppender.list.stream()
        .filter(e -> e.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  /** 通过反射拿 dispatcher 的 executor,把一个会 sleep N ms 的 task 提交进去。 */
  private void submitSlowTask(
      TaskDispatcher dispatcher, CountDownLatch started, AtomicBoolean interrupted, long sleepMs)
      throws Exception {
    Field f = TaskDispatcher.class.getDeclaredField("executor");
    f.setAccessible(true);
    ExecutorService executor = (ExecutorService) f.get(dispatcher);
    executor.execute(
        () -> {
          started.countDown();
          try {
            Thread.sleep(sleepMs);
          } catch (InterruptedException ie) {
            interrupted.set(true);
            Thread.currentThread().interrupt();
          }
        });
  }

  @SuppressWarnings("unchecked")
  private Set<Long> inFlightSet(TaskDispatcher dispatcher) throws Exception {
    Field f = TaskDispatcher.class.getDeclaredField("inFlight");
    f.setAccessible(true);
    Object v = f.get(dispatcher);
    if (v instanceof Set<?> s) {
      return (Set<Long>) s;
    }
    // 兜底(理论不到)
    Map<Long, Boolean> back = new ConcurrentHashMap<>();
    HashMap<String, Object> ignored = new HashMap<>();
    ignored.put("k", back);
    return ConcurrentHashMap.newKeySet();
  }
}
