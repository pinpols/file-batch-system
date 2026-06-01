package com.example.batch.sdk.handler;

import java.time.Duration;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * ADR-036 — 轻量重试策略,handler 内对易瞬时失败的操作(外部 HTTP / DB 连接抖动)包一层。
 *
 * <p>**注意**:这是 handler 内部业务重试,与平台 lease 超时重新派单是**两层**。平台重试针对整个 task,本策略针对 task 内某次外部调用。
 *
 * <p>用法:
 *
 * <pre>{@code
 * SdkRetryPolicy.exponential(3, Duration.ofMillis(200), Duration.ofSeconds(2))
 *     .execute(() -> httpClient.call(...));
 * }</pre>
 */
@Slf4j
public final class SdkRetryPolicy {

  private final int maxAttempts;
  private final Duration initialDelay;
  private final Duration maxDelay;
  private final double multiplier;

  private SdkRetryPolicy(int maxAttempts, Duration initialDelay, Duration maxDelay, double mult) {
    if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts >= 1");
    this.maxAttempts = maxAttempts;
    this.initialDelay = initialDelay;
    this.maxDelay = maxDelay;
    this.multiplier = mult;
  }

  /** 固定间隔重试。 */
  public static SdkRetryPolicy fixed(int maxAttempts, Duration delay) {
    return new SdkRetryPolicy(maxAttempts, delay, delay, 1.0);
  }

  /** 指数退避重试(delay = initial * multiplier^n,封顶 maxDelay)。 */
  public static SdkRetryPolicy exponential(int maxAttempts, Duration initial, Duration max) {
    return new SdkRetryPolicy(maxAttempts, initial, max, 2.0);
  }

  /** 不重试(单次)。 */
  public static SdkRetryPolicy none() {
    return new SdkRetryPolicy(1, Duration.ZERO, Duration.ZERO, 1.0);
  }

  /** 执行 op,失败按策略重试。所有尝试都失败时抛最后一次异常(包成 RuntimeException 透传给模板 catch)。 */
  public <T> T execute(Supplier<T> op) {
    RuntimeException last = null;
    Duration delay = initialDelay;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        return op.get();
      } catch (RuntimeException e) {
        last = e;
        if (attempt < maxAttempts) {
          log.warn(
              "attempt {}/{} failed: {}, retrying in {}",
              attempt,
              maxAttempts,
              e.getMessage(),
              delay);
          sleep(delay);
          delay = nextDelay(delay);
        }
      }
    }
    throw last == null ? new IllegalStateException("retry exhausted with no exception") : last;
  }

  /** 最大尝试次数(含首次)。 */
  public int maxAttempts() {
    return maxAttempts;
  }

  /** 首次退避间隔。 */
  public Duration initialDelay() {
    return initialDelay;
  }

  /**
   * 第 {@code attempt} 次失败后(1-based)的下一次退避间隔,复用本策略的 multiplier / maxDelay 封顶逻辑。 供声明式重试装饰器 {@code
   * SdkRetryableHandler} 驱动自己的重试循环时复用退避数列,避免重复实现退避数学。
   *
   * @param current 当前已用的间隔
   * @return 下一次间隔(指数策略递增封顶;固定策略恒等)
   */
  public Duration nextDelay(Duration current) {
    long next = (long) (current.toMillis() * multiplier);
    long capped = Math.min(next, maxDelay.toMillis());
    return Duration.ofMillis(capped);
  }

  /** 按当前策略阻塞退避;中断转 {@link IllegalStateException}。供装饰器复用。 */
  public static void sleepFor(Duration d) {
    sleep(d);
  }

  private static void sleep(Duration d) {
    if (d.isZero() || d.isNegative()) return;
    try {
      Thread.sleep(d.toMillis());
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("retry interrupted", ie);
    }
  }
}
