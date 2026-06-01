package com.example.batch.sdk.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.example.batch.sdk.client.BatchPlatformClientConfig;
import com.example.batch.sdk.idempotent.Idempotent;
import com.example.batch.sdk.idempotent.SdkIdempotencyRecord;
import com.example.batch.sdk.idempotent.SdkIdempotencyStore;
import com.example.batch.sdk.internal.PlatformHttpClient;
import com.example.batch.sdk.retry.RetryOn;
import com.example.batch.sdk.retry.RetryOn.Backoff;
import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskHandler;
import com.example.batch.sdk.task.SdkTaskResult;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SDK-P5 auto-wrap:验证 {@link TaskDispatcher} 注册时自动探测 {@link Idempotent} / {@link RetryOn} 并织入装饰器。
 *
 * <p>经 {@code processInWorkerThread}(同步测试入口)端到端跑 claim → execute(decorated)→ report,断言织入行为。 本模块
 * test 仅 mockito-core,故用手写 fake store + 计数 handler。
 */
class TaskDispatcherAutoWrapTest {

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

  private TaskDispatchMessage msg(String taskType, Map<String, Object> params) {
    return new TaskDispatchMessage(
        42L, "tx", "job-1", taskType, "ti-9", params, Map.of("traceId", "abc"));
  }

  // ─── handlers ────────────────────────────────────────────────────────────────

  /** 仅 @RetryOn:对 IllegalStateException 重试,前 N-1 次抛、第 N 次成功;计数执行次数。 */
  @RetryOn(
      value = IllegalStateException.class,
      maxAttempts = 4,
      initialDelayMillis = 1,
      backoff = Backoff.FIXED)
  static class RetryOnlyHandler implements SdkTaskHandler {
    final AtomicInteger executions = new AtomicInteger();
    final int failuresBeforeSuccess;

    RetryOnlyHandler(int failuresBeforeSuccess) {
      this.failuresBeforeSuccess = failuresBeforeSuccess;
    }

    @Override
    public String taskType() {
      return "tt";
    }

    @Override
    public SdkTaskResult execute(SdkTaskContext ctx) {
      int n = executions.incrementAndGet();
      if (n <= failuresBeforeSuccess) {
        throw new IllegalStateException("transient boom " + n);
      }
      return SdkTaskResult.ok("done after " + n);
    }
  }

  /** 仅 @Idempotent。 */
  @Idempotent(key = "import:{tenantId}:{orderId}")
  static class IdempotentOnlyHandler implements SdkTaskHandler {
    final AtomicInteger executions = new AtomicInteger();

    @Override
    public String taskType() {
      return "tt";
    }

    @Override
    public SdkTaskResult execute(SdkTaskContext ctx) {
      executions.incrementAndGet();
      return SdkTaskResult.ok("imported", Map.of("rows", 7));
    }
  }

  /** 同时标两注解:瞬时抛 IllegalStateException 重试到成功,且幂等去重。 */
  @Idempotent(key = "both:{tenantId}:{orderId}")
  @RetryOn(
      value = IllegalStateException.class,
      maxAttempts = 4,
      initialDelayMillis = 1,
      backoff = Backoff.FIXED)
  static class BothHandler implements SdkTaskHandler {
    final AtomicInteger executions = new AtomicInteger();
    int failuresBeforeSuccess;

    @Override
    public String taskType() {
      return "tt";
    }

    @Override
    public SdkTaskResult execute(SdkTaskContext ctx) {
      int n = executions.incrementAndGet();
      if (n <= failuresBeforeSuccess) {
        throw new IllegalStateException("transient boom " + n);
      }
      return SdkTaskResult.ok("done", Map.of("rows", 1));
    }
  }

  /** 标 @RetryOn 但只重试 IllegalStateException;execute 抛不匹配的 IllegalArgumentException。 */
  @RetryOn(
      value = IllegalStateException.class,
      maxAttempts = 4,
      initialDelayMillis = 1,
      backoff = Backoff.FIXED)
  static class MismatchHandler implements SdkTaskHandler {
    final AtomicInteger executions = new AtomicInteger();

    @Override
    public String taskType() {
      return "tt";
    }

    @Override
    public SdkTaskResult execute(SdkTaskContext ctx) {
      executions.incrementAndGet();
      throw new IllegalArgumentException("not retryable");
    }
  }

  /** 无注解。 */
  static class PlainHandler implements SdkTaskHandler {
    final AtomicInteger executions = new AtomicInteger();

    @Override
    public String taskType() {
      return "tt";
    }

    @Override
    public SdkTaskResult execute(SdkTaskContext ctx) {
      executions.incrementAndGet();
      return SdkTaskResult.ok("plain");
    }
  }

  /** 记录调用的 fake store(可预置命中结果)。 */
  static final class RecordingStore implements SdkIdempotencyStore {
    final Map<String, SdkIdempotencyRecord> map = new HashMap<>();
    final AtomicInteger findCalls = new AtomicInteger();
    final AtomicInteger recordCalls = new AtomicInteger();

    @Override
    public Optional<SdkIdempotencyRecord> find(String key) {
      findCalls.incrementAndGet();
      return Optional.ofNullable(map.get(key));
    }

    @Override
    public void record(String key, SdkIdempotencyRecord record, long ttlMillis) {
      recordCalls.incrementAndGet();
      map.put(key, record);
    }
  }

  // ─── tests ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("仅 @RetryOn:4 参 dispatcher 构造后,匹配异常会重试到成功")
  void shouldRetry_whenRetryOnHandlerThrowsTransient() {
    // arrange
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    RetryOnlyHandler handler = new RetryOnlyHandler(2); // 前 2 次抛,第 3 次成功
    dispatcher = new TaskDispatcher(config, Map.of("tt", handler), http, null);

    // act
    dispatcher.processInWorkerThread(msg("tt", Map.of("orderId", "o1")));

    // assert
    assertThat(handler.executions.get()).isEqualTo(3);
  }

  @Test
  @DisplayName("仅 @Idempotent + store:同 key 第二次命中缓存,handler 不再执行")
  void shouldHitCache_whenSameKeyDispatchedTwice() {
    // arrange
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    IdempotentOnlyHandler handler = new IdempotentOnlyHandler();
    RecordingStore store = new RecordingStore();
    dispatcher = new TaskDispatcher(config, Map.of("tt", handler), http, store);

    // act
    dispatcher.processInWorkerThread(msg("tt", Map.of("orderId", "o1")));
    dispatcher.processInWorkerThread(msg("tt", Map.of("orderId", "o1")));

    // assert
    assertThat(handler.executions.get()).isEqualTo(1);
    assertThat(store.recordCalls.get()).isEqualTo(1);
    assertThat(store.findCalls.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("同时标两注解(idempotent 外/retry 内):单次重试到成功 + 记一次幂等;再次同 key 命中、不重试")
  void shouldRetryThenIdempotent_whenBothAnnotated() {
    // arrange
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    BothHandler handler = new BothHandler();
    handler.failuresBeforeSuccess = 1; // 第一次执行内:1 次瞬时失败 + 1 次成功
    RecordingStore store = new RecordingStore();
    dispatcher = new TaskDispatcher(config, Map.of("tt", handler), http, store);

    // act — 第一次:retry 内层吃掉瞬时异常,成功后 idempotent 外层 record
    dispatcher.processInWorkerThread(msg("tt", Map.of("orderId", "o1")));

    // assert (a) + (b)
    assertThat(handler.executions.get()).isEqualTo(2); // 1 失败 + 1 成功
    assertThat(store.recordCalls.get()).isEqualTo(1);

    // act — 第二次同 key:idempotent 命中,handler 不再被调,也不进 retry
    handler.failuresBeforeSuccess = 99; // 若再被调用必失败 → 证明根本没调
    dispatcher.processInWorkerThread(msg("tt", Map.of("orderId", "o1")));

    // assert (c)
    assertThat(handler.executions.get()).isEqualTo(2); // 没增加
    assertThat(store.recordCalls.get()).isEqualTo(1); // 没再记
    assertThat(store.findCalls.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("@Idempotent 但 store=null(3 参构造器)→ 构造期抛 IllegalStateException,消息含 taskType")
  void shouldFailFast_whenIdempotentHandlerButNoStore() {
    // arrange
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    Map<String, SdkTaskHandler> handlers = Map.of("tt", new IdempotentOnlyHandler());

    // act + assert
    assertThatThrownBy(() -> new TaskDispatcher(config, handlers, http))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("tt")
        .hasMessageContaining("SdkIdempotencyStore");
  }

  @Test
  @DisplayName("无注解 handler → 原样,不包装,行为不变")
  void shouldNotWrap_whenNoAnnotation() {
    // arrange
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    PlainHandler handler = new PlainHandler();
    dispatcher = new TaskDispatcher(config, Map.of("tt", handler), http, null);

    // act
    dispatcher.processInWorkerThread(msg("tt", Map.of("orderId", "o1")));

    // assert
    assertThat(handler.executions.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("retryOn 不匹配的异常透传,不重试(IOException 不在 value 列表)")
  void shouldNotRetry_whenExceptionTypeMismatch() {
    // arrange
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    MismatchHandler handler = new MismatchHandler();
    dispatcher = new TaskDispatcher(config, Map.of("tt", handler), http, null);

    // act — dispatcher 兜底把异常转 fail report,不抛出
    dispatcher.processInWorkerThread(msg("tt", Map.of("orderId", "o1")));

    // assert
    assertThat(handler.executions.get()).isEqualTo(1);
  }
}
