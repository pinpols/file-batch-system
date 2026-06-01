package com.example.batch.sdk.idempotent;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskHandler;
import com.example.batch.sdk.task.SdkTaskResult;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A.3 声明式幂等装饰器单测。
 *
 * <p>本模块 test 仅 mockito-core(无 mockito-junit-jupiter),为保持轻量这里用手写 fake store(InMemoryStore /
 * RecordingStore)而非 {@code @Mock} / MockitoExtension。
 */
class SdkIdempotentHandlerTest {

  /** 标注幂等的测试 handler:key 含上下文 + 参数占位符;记录执行次数。 */
  @Idempotent(key = "import:{tenantId}:{orderId}")
  static class AnnotatedHandler implements SdkTaskHandler {
    final AtomicInteger executions = new AtomicInteger();
    boolean failBusiness;

    @Override
    public String taskType() {
      return "tenant_import";
    }

    @Override
    public SdkTaskResult execute(SdkTaskContext ctx) {
      executions.incrementAndGet();
      return failBusiness
          ? SdkTaskResult.fail("business boom")
          : SdkTaskResult.ok("imported", Map.of("rows", 10));
    }
  }

  /** 未标注的 handler。 */
  static class PlainHandler implements SdkTaskHandler {
    @Override
    public String taskType() {
      return "plain";
    }

    @Override
    public SdkTaskResult execute(SdkTaskContext ctx) {
      return SdkTaskResult.ok("done");
    }
  }

  /** 记录调用的 fake store(可预置命中结果)。 */
  static final class RecordingStore implements SdkIdempotencyStore {
    final Map<String, SdkIdempotencyRecord> map = new HashMap<>();
    final AtomicInteger findCalls = new AtomicInteger();
    String lastRecordKey;
    long lastTtl;

    @Override
    public Optional<SdkIdempotencyRecord> find(String key) {
      findCalls.incrementAndGet();
      return Optional.ofNullable(map.get(key));
    }

    @Override
    public void record(String key, SdkIdempotencyRecord record, long ttlMillis) {
      this.lastRecordKey = key;
      this.lastTtl = ttlMillis;
      map.put(key, record);
    }
  }

  private static SdkTaskContext ctx(Map<String, Object> params) {
    return new SdkTaskContext("t1", "job", "ti", 1L, "w1", params, Map.of());
  }

  @Test
  @DisplayName("未标 @Idempotent → wrap 原样返回,不包装")
  void shouldReturnDelegate_whenNotAnnotated() {
    // arrange
    PlainHandler plain = new PlainHandler();

    // act
    SdkTaskHandler wrapped = SdkIdempotentHandler.wrap(plain, new RecordingStore());

    // assert
    assertThat(wrapped).isSameAs(plain);
  }

  @Test
  @DisplayName("未命中 → 执行 + 成功记录;key 由占位符解析")
  void shouldExecuteAndRecord_whenKeyMisses() {
    // arrange
    RecordingStore store = new RecordingStore();
    AnnotatedHandler handler = new AnnotatedHandler();
    SdkTaskHandler wrapped = SdkIdempotentHandler.wrap(handler, store);

    // act
    SdkTaskResult result = wrapped.execute(ctx(Map.of("orderId", "A100")));

    // assert
    assertThat(result.success()).isTrue();
    assertThat(handler.executions).hasValue(1);
    assertThat(store.lastRecordKey).isEqualTo("import:t1:A100");
  }

  @Test
  @DisplayName("命中 → 跳过执行,返已记录结果")
  void shouldSkipExecution_whenKeyHits() {
    // arrange
    RecordingStore store = new RecordingStore();
    store.map.put(
        "import:t1:A100", new SdkIdempotencyRecord("imported(cached)", Map.of("rows", 99)));
    AnnotatedHandler handler = new AnnotatedHandler();
    SdkTaskHandler wrapped = SdkIdempotentHandler.wrap(handler, store);

    // act
    SdkTaskResult result = wrapped.execute(ctx(Map.of("orderId", "A100")));

    // assert
    assertThat(result.success()).isTrue();
    assertThat(result.message()).isEqualTo("imported(cached)");
    assertThat(result.output()).containsEntry("rows", 99);
    assertThat(handler.executions).hasValue(0);
    assertThat(store.lastRecordKey).isNull();
  }

  @Test
  @DisplayName("业务失败 → 不记录(留给重试)")
  void shouldNotRecord_whenBusinessFails() {
    // arrange
    RecordingStore store = new RecordingStore();
    AnnotatedHandler handler = new AnnotatedHandler();
    handler.failBusiness = true;
    SdkTaskHandler wrapped = SdkIdempotentHandler.wrap(handler, store);

    // act
    SdkTaskResult result = wrapped.execute(ctx(Map.of("orderId", "A100")));

    // assert
    assertThat(result.success()).isFalse();
    assertThat(handler.executions).hasValue(1);
    assertThat(store.lastRecordKey).isNull();
  }

  @Test
  @DisplayName("占位符解析不到值 → fail,不执行业务、不查 store")
  void shouldFail_whenKeyPlaceholderUnresolved() {
    // arrange:缺 orderId
    RecordingStore store = new RecordingStore();
    AnnotatedHandler handler = new AnnotatedHandler();
    SdkTaskHandler wrapped = SdkIdempotentHandler.wrap(handler, store);

    // act
    SdkTaskResult result = wrapped.execute(ctx(Map.of()));

    // assert
    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("idempotent key resolution failed");
    assertThat(handler.executions).hasValue(0);
    assertThat(store.findCalls).hasValue(0);
  }

  @Test
  @DisplayName("ttlMillis 透传给 store.record")
  void shouldPassTtlToStore() {
    // arrange
    RecordingStore store = new RecordingStore();
    SdkTaskHandler wrapped = SdkIdempotentHandler.wrap(new TtlHandler(), store);

    // act
    wrapped.execute(ctx(Map.of("orderId", "A100")));

    // assert
    assertThat(store.lastTtl).isEqualTo(60_000L);
  }

  @Idempotent(key = "ttl:{orderId}", ttlMillis = 60_000L)
  static class TtlHandler implements SdkTaskHandler {
    @Override
    public String taskType() {
      return "ttl";
    }

    @Override
    public SdkTaskResult execute(SdkTaskContext ctx) {
      return SdkTaskResult.ok("ok");
    }
  }

  @Test
  @DisplayName("同 key 第二次跳过执行,不同 key 各执行一次")
  void shouldDedupAcrossCalls() {
    // arrange
    RecordingStore store = new RecordingStore();
    AnnotatedHandler handler = new AnnotatedHandler();
    SdkTaskHandler wrapped = SdkIdempotentHandler.wrap(handler, store);

    // act
    wrapped.execute(ctx(Map.of("orderId", "A100")));
    wrapped.execute(ctx(Map.of("orderId", "A100")));
    wrapped.execute(ctx(Map.of("orderId", "B200")));

    // assert:A100 执行 1 次(第二次命中),B200 执行 1 次 → 共 2
    assertThat(handler.executions).hasValue(2);
  }

  @Test
  @DisplayName("NoOp store → 永不去重,每次都执行")
  void shouldNeverDedup_withNoOpStore() {
    // arrange
    AnnotatedHandler handler = new AnnotatedHandler();
    SdkTaskHandler wrapped = SdkIdempotentHandler.wrap(handler, new SdkIdempotencyStore.NoOp());

    // act
    wrapped.execute(ctx(Map.of("orderId", "A100")));
    wrapped.execute(ctx(Map.of("orderId", "A100")));

    // assert
    assertThat(handler.executions).hasValue(2);
  }
}
