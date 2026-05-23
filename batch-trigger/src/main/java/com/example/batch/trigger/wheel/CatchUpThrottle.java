package com.example.batch.trigger.wheel;

import java.time.Clock;
import java.time.Duration;

/**
 * 进程内 fixed-interval throttle — 用于 catch-up AUTO 策略防雪崩(详见 quartz-replacement-design.md §9.3 R-X)。
 *
 * <p>语义:严格平滑限流(无 burst),每秒 N 次 {@link #acquire()} 调用,超出则阻塞当前线程到下一个 token 可用。catch-up 场景需要"排队补
 * fire"(而非"丢 fire"),所以选阻塞模式。
 *
 * <p>与 Guava {@code RateLimiter}(SmoothBursty)的区别:
 *
 * <ul>
 *   <li>不允许 burst — 1 秒空档不会累积 token,严格按间隔放行(catch-up 雪崩防护场景下更安全)
 *   <li>{@link Clock} 可注入,测试时用 stub clock,无需真 sleep
 *   <li>0 第三方依赖
 * </ul>
 *
 * <p>线程安全:简单 {@code synchronized} 保护 {@link #nextAvailableNanos};catch-up throttle 不是热路径, 锁竞争可忽略。
 */
public class CatchUpThrottle {

  // 全程用毫秒空间计算，避免纳秒→毫秒整数截断累积误差（rate=3 时 nanos/1e6=333ms 会让实际速率 ~3.003/s）。
  // 用 Math.ceil 上取整保证"严格不超出 ratePerSecond"——宁可略慢，不可雪崩。
  private final long intervalMillis;
  private final Clock clock;
  private long nextAvailableMillis;

  public CatchUpThrottle(double ratePerSecond) {
    this(ratePerSecond, Clock.systemUTC());
  }

  /**
   * @param ratePerSecond 每秒允许的 acquire 次数;> 0
   * @param clock 时钟来源;测试时注入 stub
   */
  public CatchUpThrottle(double ratePerSecond, Clock clock) {
    if (ratePerSecond <= 0) {
      throw new IllegalArgumentException("ratePerSecond must be > 0, got " + ratePerSecond);
    }
    this.intervalMillis = (long) Math.ceil(Duration.ofSeconds(1).toMillis() / ratePerSecond);
    this.clock = clock;
    this.nextAvailableMillis = clock.millis();
  }

  /** 预约下一个 token,返回**调用方应等待的毫秒数**(0 = 立即可消费)。 纯算法,不阻塞,易测试。 */
  public synchronized long reserveSlot() {
    long now = clock.millis();
    long sleepMillis;
    if (now < nextAvailableMillis) {
      sleepMillis = nextAvailableMillis - now;
    } else {
      sleepMillis = 0;
      nextAvailableMillis = now;
    }
    nextAvailableMillis += intervalMillis;
    return sleepMillis;
  }

  /**
   * 阻塞直到下一个 token 可用并消费它。生产 catch-up 路径用,测试覆盖走 {@link #reserveSlot()}。
   *
   * <p><b>线程上下文(R-arch-audit-2026-05-23 P1)</b>:本方法包含 {@link Thread#sleep},
   * 必须从可阻塞线程 (e.g. {@code triggerFireExecutor} 异步线程池) 调用,**严禁**在 Netty
   * {@code HashedWheelTimer} 的 worker 线程内直接调用 — 否则会阻塞整个 wheel tick
   * 处理,导致其他等待 fire 的 task 全部延迟。
   *
   * <p>当前 fire 路径 (HashedWheelTriggerScheduler.fire → handleMisfire → acquire) 通过 P0 5a
   * 修复将 DB 操作异步化到独立 executor 后,sleep 不再阻塞 wheel worker。若新增调用方,务必
   * 复核该调用方线程上下文。
   */
  public void acquire() throws InterruptedException {
    long sleepMillis = reserveSlot();
    if (sleepMillis > 0) {
      Thread.sleep(sleepMillis);
    }
  }

  /** 测试 / 监控用:返回当前 next-available 时刻(毫秒);非阻塞。 */
  public synchronized long nextAvailableMillisForTest() {
    return nextAvailableMillis;
  }
}
