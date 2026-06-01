package com.example.batch.sdk.retry;

import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskHandler;
import com.example.batch.sdk.task.SdkTaskResult;
import com.example.batch.sdk.task.SdkTaskTypeDescriptor;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;

/**
 * A.4 — 声明式重试的织入装饰器。包一个被 {@link RetryOn} 标注的 {@link SdkTaskHandler},对整体执行遇到指定异常时按退避重试。
 *
 * <p><b>为何用装饰器</b>:与 {@link com.example.batch.sdk.idempotent.SdkIdempotentHandler} 同理 —— SDK core 禁
 * Spring,改用显式 {@link #wrap}。退避数学复用 {@link SdkRetryPolicy}(initial / nextDelay / sleepFor)。
 *
 * <p><b>两种失败形态都覆盖</b>:
 *
 * <ul>
 *   <li>裸 handler 直接抛异常 → catch 后判定是否匹配重试;
 *   <li>ADR-036 模板基类已把异常吞成 {@link SdkTaskResult#fail} 并挂 {@link SdkTaskResult#error()} → 检查 error
 *       是否匹配,匹配则重试。
 * </ul>
 *
 * <p>不匹配的异常 / 失败原样透传不重试。达上限仍失败 → 返回最后一次失败结果(或最后异常转 fail)。
 */
@Slf4j
public final class SdkRetryableHandler implements SdkTaskHandler {

  private final SdkTaskHandler delegate;
  private final RetryOn annotation;
  private final SdkRetryPolicy policy;

  private SdkRetryableHandler(SdkTaskHandler delegate, RetryOn annotation) {
    this.delegate = delegate;
    this.annotation = annotation;
    this.policy = buildPolicy(annotation);
  }

  /**
   * 若 {@code delegate} 标了 {@link RetryOn} 则包装,否则原样返回。等价 {@link #wrapAround}(delegate, delegate)。
   */
  public static SdkTaskHandler wrap(SdkTaskHandler delegate) {
    return wrapAround(delegate, delegate);
  }

  /**
   * 组合友好工厂:从 {@code source} 的运行时类读 {@link RetryOn},命中则把 {@code delegate} 包一层重试,否则原样返回 {@code
   * delegate}。
   *
   * <p>与 {@link #wrap} 的区别:注解从 {@code source}(原始 handler)读,而非从可能已被其他装饰器包过的 {@code delegate} 读。这样多层
   * 装饰器嵌套时,内层 wrapper 的 class 没有注解也不影响判定 —— 上层只需把原始 handler 作为 {@code source} 传入。
   *
   * @param source 提供 {@link RetryOn} 注解的原始 handler
   * @param delegate 实际被包装执行的 handler(可能已被内层装饰器包过)
   */
  public static SdkTaskHandler wrapAround(SdkTaskHandler source, SdkTaskHandler delegate) {
    RetryOn ann = source.getClass().getAnnotation(RetryOn.class);
    if (ann == null) {
      return delegate;
    }
    return new SdkRetryableHandler(delegate, ann);
  }

  private static SdkRetryPolicy buildPolicy(RetryOn ann) {
    Duration initial = Duration.ofMillis(ann.initialDelayMillis());
    return switch (ann.backoff()) {
      case FIXED -> SdkRetryPolicy.fixed(ann.maxAttempts(), initial);
      case EXPONENTIAL ->
          SdkRetryPolicy.exponential(
              ann.maxAttempts(), initial, Duration.ofMillis(ann.maxDelayMillis()));
    };
  }

  @Override
  public String taskType() {
    return delegate.taskType();
  }

  @Override
  public SdkTaskResult execute(SdkTaskContext ctx) {
    int maxAttempts = policy.maxAttempts();
    Duration delay = policy.initialDelay();
    SdkTaskResult lastResult = null;
    RuntimeException lastThrown = null;

    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      Throwable failure;
      try {
        SdkTaskResult result = delegate.execute(ctx);
        if (result.success()) {
          return result;
        }
        lastResult = result;
        failure = result.error();
      } catch (RuntimeException ex) {
        lastThrown = ex;
        lastResult = null;
        failure = ex;
      }

      if (!isRetryable(failure)) {
        return resolveFinal(lastResult, lastThrown);
      }
      if (attempt < maxAttempts) {
        log.warn(
            "retryable handler {} attempt {}/{} failed ({}), retrying in {}",
            taskType(),
            attempt,
            maxAttempts,
            failure == null ? "no-error" : failure.getClass().getSimpleName(),
            delay);
        SdkRetryPolicy.sleepFor(delay);
        delay = policy.nextDelay(delay);
      }
    }
    return resolveFinal(lastResult, lastThrown);
  }

  /** failure 非 null 且 isInstanceOf 任一声明类型才重试。 */
  private boolean isRetryable(Throwable failure) {
    if (failure == null) {
      return false;
    }
    for (Class<? extends Throwable> type : annotation.value()) {
      if (type.isInstance(failure)) {
        return true;
      }
    }
    return false;
  }

  /** 收敛最终返回:有 result 用 result(保留 output/message),否则把最后异常转 fail;再否则透传抛出。 */
  private SdkTaskResult resolveFinal(SdkTaskResult lastResult, RuntimeException lastThrown) {
    if (lastResult != null) {
      return lastResult;
    }
    if (lastThrown != null) {
      throw lastThrown;
    }
    return SdkTaskResult.fail("retryable handler " + taskType() + " produced no result");
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
