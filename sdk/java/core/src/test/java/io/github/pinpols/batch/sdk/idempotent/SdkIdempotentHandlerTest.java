package io.github.pinpols.batch.sdk.idempotent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import io.github.pinpols.batch.sdk.task.SdkTaskHandler;
import io.github.pinpols.batch.sdk.task.SdkTaskResult;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    final Map<String, SdkIdempotencyEntity> map = new HashMap<>();
    final Set<String> placeholders = new HashSet<>();
    final AtomicInteger findCalls = new AtomicInteger();
    String lastRecordKey;
    long lastTtl;

    @Override
    public boolean tryAcquire(String key, long ttlMillis) {
      if (map.containsKey(key) || placeholders.contains(key)) {
        return false;
      }
      placeholders.add(key);
      return true;
    }

    @Override
    public Optional<SdkIdempotencyEntity> find(String key) {
      findCalls.incrementAndGet();
      return Optional.ofNullable(map.get(key));
    }

    @Override
    public void record(String key, SdkIdempotencyEntity record, long ttlMillis) {
      this.lastRecordKey = key;
      this.lastTtl = ttlMillis;
      map.put(key, record);
      placeholders.remove(key);
    }

    @Override
    public void release(String key) {
      placeholders.remove(key);
    }
  }

  private static SdkTaskContext ctx(Map<String, Object> params) {
    return new SdkTaskContext("t1", "job", "ti", 1L, "w1", params, Map.of());
  }

  @Test
  @DisplayName("未标 @Idempotent → wrap 原样返回,不包装")
  void shouldReturnDelegate_whenNotAnnotated() {
    // 准备
    PlainHandler plain = new PlainHandler();

    // 执行
    SdkTaskHandler wrapped = SdkIdempotentHandler.wrap(plain, new RecordingStore());

    // 断言
    assertThat(wrapped).isSameAs(plain);
  }

  @Test
  @DisplayName("未命中 → 执行 + 成功记录;key 由占位符解析")
  void shouldExecuteAndRecord_whenKeyMisses() {
    // 准备
    RecordingStore store = new RecordingStore();
    AnnotatedHandler handler = new AnnotatedHandler();
    SdkTaskHandler wrapped = SdkIdempotentHandler.wrap(handler, store);

    // 执行
    SdkTaskResult result = wrapped.execute(ctx(Map.of("orderId", "A100")));

    // 断言
    assertThat(result.success()).isTrue();
    assertThat(handler.executions).hasValue(1);
    assertThat(store.lastRecordKey).isEqualTo("import:t1:A100");
  }

  @Test
  @DisplayName("命中 → 跳过执行,返已记录结果")
  void shouldSkipExecution_whenKeyHits() {
    // 准备
    RecordingStore store = new RecordingStore();
    store.map.put(
        "import:t1:A100", new SdkIdempotencyEntity("imported(cached)", Map.of("rows", 99)));
    AnnotatedHandler handler = new AnnotatedHandler();
    SdkTaskHandler wrapped = SdkIdempotentHandler.wrap(handler, store);

    // 执行
    SdkTaskResult result = wrapped.execute(ctx(Map.of("orderId", "A100")));

    // 断言
    assertThat(result.success()).isTrue();
    assertThat(result.message()).isEqualTo("imported(cached)");
    assertThat(result.output()).containsEntry("rows", 99);
    assertThat(handler.executions).hasValue(0);
    assertThat(store.lastRecordKey).isNull();
  }

  @Test
  @DisplayName("已有执行中占位但未回填结果 → 不执行业务,返回 in-flight 失败供平台重试")
  void shouldReturnInFlight_whenPlaceholderExistsWithoutRecord() {
    // 准备
    RecordingStore store = new RecordingStore();
    store.placeholders.add("import:t1:A100");
    AnnotatedHandler handler = new AnnotatedHandler();
    SdkTaskHandler wrapped = SdkIdempotentHandler.wrap(handler, store);

    // 执行
    SdkTaskResult result = wrapped.execute(ctx(Map.of("orderId", "A100")));

    // 断言
    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("in-flight");
    assertThat(handler.executions).hasValue(0);
    assertThat(store.findCalls).hasValue(1);
  }

  @Test
  @DisplayName("业务失败 → 不记录(留给重试)")
  void shouldNotRecord_whenBusinessFails() {
    // 准备
    RecordingStore store = new RecordingStore();
    AnnotatedHandler handler = new AnnotatedHandler();
    handler.failBusiness = true;
    SdkTaskHandler wrapped = SdkIdempotentHandler.wrap(handler, store);

    // 执行
    SdkTaskResult result = wrapped.execute(ctx(Map.of("orderId", "A100")));

    // 断言
    assertThat(result.success()).isFalse();
    assertThat(handler.executions).hasValue(1);
    assertThat(store.lastRecordKey).isNull();
    assertThat(store.placeholders).doesNotContain("import:t1:A100");
  }

  @Test
  @DisplayName("占位符解析不到值 → fail,不执行业务、不查 store")
  void shouldFail_whenKeyPlaceholderUnresolved() {
    // 准备:缺 orderId
    RecordingStore store = new RecordingStore();
    AnnotatedHandler handler = new AnnotatedHandler();
    SdkTaskHandler wrapped = SdkIdempotentHandler.wrap(handler, store);

    // 执行
    SdkTaskResult result = wrapped.execute(ctx(Map.of()));

    // 断言
    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("idempotent key resolution failed");
    assertThat(handler.executions).hasValue(0);
    assertThat(store.findCalls).hasValue(0);
  }

  @Test
  @DisplayName("ttlMillis 透传给 store.record")
  void shouldPassTtlToStore() {
    // 准备
    RecordingStore store = new RecordingStore();
    SdkTaskHandler wrapped = SdkIdempotentHandler.wrap(new TtlHandler(), store);

    // 执行
    wrapped.execute(ctx(Map.of("orderId", "A100")));

    // 断言
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
    // 准备
    RecordingStore store = new RecordingStore();
    AnnotatedHandler handler = new AnnotatedHandler();
    SdkTaskHandler wrapped = SdkIdempotentHandler.wrap(handler, store);

    // 执行
    wrapped.execute(ctx(Map.of("orderId", "A100")));
    wrapped.execute(ctx(Map.of("orderId", "A100")));
    wrapped.execute(ctx(Map.of("orderId", "B200")));

    // 断言:A100 执行 1 次(第二次命中),B200 执行 1 次 → 共 2
    assertThat(handler.executions).hasValue(2);
  }

  /** find() 抛运行时异常的 fake store —— 验证装饰器不吞,透传给 dispatcher 回退。 */
  static final class ThrowingStore implements SdkIdempotencyStore {
    @Override
    public boolean tryAcquire(String key, long ttlMillis) {
      throw new IllegalStateException("store backend down");
    }

    @Override
    public Optional<SdkIdempotencyEntity> find(String key) {
      return Optional.empty();
    }

    @Override
    public void record(String key, SdkIdempotencyEntity record, long ttlMillis) {}

    @Override
    public void release(String key) {}
  }

  static final class RecordThrowingStore implements SdkIdempotencyStore {
    final Set<String> placeholders = new HashSet<>();
    final AtomicInteger releaseCalls = new AtomicInteger();

    @Override
    public boolean tryAcquire(String key, long ttlMillis) {
      placeholders.add(key);
      return true;
    }

    @Override
    public Optional<SdkIdempotencyEntity> find(String key) {
      return Optional.empty();
    }

    @Override
    public void record(String key, SdkIdempotencyEntity record, long ttlMillis) {
      throw new IllegalStateException("record backend down");
    }

    @Override
    public void release(String key) {
      releaseCalls.incrementAndGet();
      placeholders.remove(key);
    }
  }

  @Test
  @DisplayName("store.tryAcquire() 抛异常 → 装饰器不吞,原样透传(由 dispatcher 回退转 fail report),业务不执行")
  void shouldPropagate_whenStoreTryAcquireThrows() {
    // 准备
    AnnotatedHandler handler = new AnnotatedHandler();
    SdkTaskHandler wrapped = SdkIdempotentHandler.wrap(handler, new ThrowingStore());

    // 执行并断言:find 抛的 RuntimeException 直接冒泡(契约:不在装饰器层吞)
    assertThatThrownBy(() -> wrapped.execute(ctx(Map.of("orderId", "A100"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("store backend down");
    assertThat(handler.executions).hasValue(0); // tryAcquire 先于 execute,故业务未跑
  }

  @Test
  @DisplayName("store.record() 抛异常 → 不释放占位,避免成功副作用后立即重复执行")
  void shouldKeepPlaceholder_whenStoreRecordThrows() {
    // 准备
    AnnotatedHandler handler = new AnnotatedHandler();
    RecordThrowingStore store = new RecordThrowingStore();
    SdkTaskHandler wrapped = SdkIdempotentHandler.wrap(handler, store);

    // 执行并断言
    assertThatThrownBy(() -> wrapped.execute(ctx(Map.of("orderId", "A100"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("record backend down");
    assertThat(handler.executions).hasValue(1);
    assertThat(store.releaseCalls).hasValue(0);
    assertThat(store.placeholders).contains("import:t1:A100");
  }

  @Test
  @DisplayName("NoOp store → 永不去重,每次都执行")
  void shouldNeverDedup_withNoOpStore() {
    // 准备
    AnnotatedHandler handler = new AnnotatedHandler();
    SdkTaskHandler wrapped = SdkIdempotentHandler.wrap(handler, new SdkIdempotencyStore.NoOp());

    // 执行
    wrapped.execute(ctx(Map.of("orderId", "A100")));
    wrapped.execute(ctx(Map.of("orderId", "A100")));

    // 断言
    assertThat(handler.executions).hasValue(2);
  }
}
