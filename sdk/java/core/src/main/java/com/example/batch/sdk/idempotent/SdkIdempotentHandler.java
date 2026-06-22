package com.example.batch.sdk.idempotent;

import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskHandler;
import com.example.batch.sdk.task.SdkTaskResult;
import com.example.batch.sdk.task.SdkTaskTypeDescriptor;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * A.3 — 声明式幂等的织入装饰器。包一个被 {@link Idempotent} 标注的 {@link SdkTaskHandler},在 {@code execute} 前后织入去重:命中
 * key → 跳过执行返已有结果;抢到执行权 → 执行,成功才记录(失败释放占位,留给平台 / 重试)。
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
   * 若 {@code delegate} 标了 {@link Idempotent} 则包装,否则原样返回。等价 {@link #wrapAround}(delegate, delegate,
   * store)。
   *
   * @param delegate 被包 handler
   * @param store 租户注入的去重存储
   * @return 包装后的 handler(或原 handler)
   */
  public static SdkTaskHandler wrap(SdkTaskHandler delegate, SdkIdempotencyStore store) {
    return wrapAround(delegate, delegate, store);
  }

  /**
   * 组合友好工厂:从 {@code source} 的运行时类读 {@link Idempotent},命中则把 {@code delegate} 包一层幂等,否则原样返回 {@code
   * delegate}。
   *
   * <p>与 {@link #wrap} 的区别:注解从 {@code source}(原始 handler)读,而非从可能已被其他装饰器包过的 {@code delegate} 读 —— 多层
   * 嵌套时内层 wrapper 的 class 没有注解也不影响判定。
   *
   * <p>命中注解但 {@code store == null} → fail-fast 抛 {@link IllegalStateException}(声明了幂等却没注入存储,属装配错误,
   * 越早暴露越好);无注解则不要求 store。
   *
   * @param source 提供 {@link Idempotent} 注解的原始 handler
   * @param delegate 实际被包装执行的 handler(可能已被内层装饰器包过)
   * @param store 租户注入的去重存储(命中注解时必填)
   */
  public static SdkTaskHandler wrapAround(
      SdkTaskHandler source, SdkTaskHandler delegate, SdkIdempotencyStore store) {
    Idempotent ann = source.getClass().getAnnotation(Idempotent.class);
    if (ann == null) {
      return delegate;
    }
    if (store == null) {
      throw new IllegalStateException(
          "handler taskType=" + source.taskType() + " 声明了 @Idempotent 但未注入 SdkIdempotencyStore");
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

    boolean acquired = store.tryAcquire(key, annotation.ttlMillis());
    if (!acquired) {
      Optional<SdkIdempotencyEntity> existing = store.find(key);
      if (existing.isPresent()) {
        log.info("idempotent hit: taskType={} key={} — skipping execution", taskType(), key);
        return existing.get().toResult();
      }
      log.info(
          "idempotent in-flight: taskType={} key={} — another execution holds the key",
          taskType(),
          key);
      return SdkTaskResult.fail(
          "idempotent key " + key + " is in-flight; retry after the holder completes");
    }

    SdkTaskResult result;
    try {
      result = delegate.execute(ctx);
    } catch (RuntimeException | Error ex) {
      store.release(key);
      throw ex;
    }
    if (result.success()) {
      store.record(key, SdkIdempotencyEntity.ofResult(result), annotation.ttlMillis());
    } else {
      store.release(key);
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
