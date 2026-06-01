package com.example.batch.sdk.idempotent;

import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskHandler;
import com.example.batch.sdk.task.SdkTaskResult;
import com.example.batch.sdk.task.SdkTaskTypeDescriptor;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * A.3 — 声明式幂等的织入装饰器。包一个被 {@link Idempotent} 标注的 {@link SdkTaskHandler},在 {@code execute} 前后织入去重:命中
 * key → 跳过执行返已有结果;未命中 → 执行,成功才记录(失败不记录,留给平台 / 重试)。
 *
 * <p><b>为何用装饰器而非 Spring AOP</b>:SDK core 禁引 Spring,无法用 {@code @Aspect} 织入。改用显式 {@link #wrap} 在
 * worker 注册 handler 时手工包一层(或由接入方的容器在 bean 后置处理时包),零框架依赖。
 *
 * <p>典型用法:
 *
 * <pre>{@code
 * SdkTaskHandler handler = SdkIdempotentHandler.wrap(new MyImportHandler(), idempotencyStore);
 * dispatcher.register(handler);   // taskType / cancel / descriptor 均透传给被包 handler
 * }</pre>
 *
 * <p>被包 handler 未标 {@link Idempotent} → {@link #wrap} 原样返回(不包),避免无谓开销。
 */
@Slf4j
public final class SdkIdempotentHandler implements SdkTaskHandler {

  private final SdkTaskHandler delegate;
  private final SdkIdempotencyStore store;
  private final Idempotent annotation;

  private SdkIdempotentHandler(
      SdkTaskHandler delegate, SdkIdempotencyStore store, Idempotent annotation) {
    this.delegate = delegate;
    this.store = store;
    this.annotation = annotation;
  }

  /**
   * 若 {@code delegate} 标了 {@link Idempotent} 则包装,否则原样返回。
   *
   * @param delegate 被包 handler
   * @param store 租户注入的去重存储
   * @return 包装后的 handler(或原 handler)
   */
  public static SdkTaskHandler wrap(SdkTaskHandler delegate, SdkIdempotencyStore store) {
    Idempotent ann = delegate.getClass().getAnnotation(Idempotent.class);
    if (ann == null) {
      return delegate;
    }
    return new SdkIdempotentHandler(delegate, store, ann);
  }

  @Override
  public String taskType() {
    return delegate.taskType();
  }

  @Override
  public SdkTaskResult execute(SdkTaskContext ctx) {
    String key;
    try {
      key = IdempotencyKeyResolver.resolve(annotation.key(), ctx);
    } catch (IllegalArgumentException ex) {
      return SdkTaskResult.fail(
          "idempotent key resolution failed for taskType=" + taskType() + ": " + ex.getMessage(),
          ex);
    }

    Optional<SdkIdempotencyRecord> existing = store.find(key);
    if (existing.isPresent()) {
      log.info("idempotent hit: taskType={} key={} — skipping execution", taskType(), key);
      return existing.get().toResult();
    }

    SdkTaskResult result = delegate.execute(ctx);
    if (result.success()) {
      store.record(key, SdkIdempotencyRecord.ofResult(result), annotation.ttlMillis());
    }
    return result;
  }

  @Override
  public void cancel(String taskInstanceId) {
    delegate.cancel(taskInstanceId);
  }

  @Override
  public SdkTaskTypeDescriptor descriptor() {
    return delegate.descriptor();
  }
}
