package io.github.pinpols.batch.common.logging;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 按 key 节流的日志门控。
 *
 * <p>用法:对"重复性高、单次价值递减"的 WARN(如调度找不到 worker、配置错任务反复失败), 把决策委托给 {@link #evaluate(String)}——首次或冷却到期返回
 * {@code shouldLog=true} 并带上窗口内已抑制次数;否则返回 {@code shouldLog=false},内部累计 suppressed。
 *
 * <p>调用方自行决定 WARN/INFO 级别与上下文字段;本类不持有 SLF4J 引用,因此 logger 仍按调用类。
 */
public final class ThrottledLogger {

  private final long cooldownNanos;
  private final LongSupplier clock;
  private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

  public ThrottledLogger(Duration cooldown) {
    this(cooldown, System::nanoTime);
  }

  /** 测试入口:允许注入虚拟时钟。 */
  public ThrottledLogger(Duration cooldown, LongSupplier nanoClock) {
    if (cooldown == null || cooldown.isNegative() || cooldown.isZero()) {
      throw new IllegalArgumentException("cooldown must be positive");
    }
    this.cooldownNanos = cooldown.toNanos();
    this.clock = nanoClock;
  }

  /** 评估 key 是否应该立即输出日志。 */
  public Decision evaluate(String key) {
    if (key == null) {
      return new Decision(true, 0L);
    }
    long now = clock.getAsLong();
    State s = states.computeIfAbsent(key, k -> new State());
    synchronized (s) {
      if (now >= s.nextAllowedAt) {
        long suppressed = s.suppressedCount;
        s.suppressedCount = 0L;
        s.nextAllowedAt = now + cooldownNanos;
        return new Decision(true, suppressed);
      }
      s.suppressedCount++;
      return new Decision(false, 0L);
    }
  }

  /** 节流决策。{@code suppressedSincePrevious} 仅在 {@code shouldLog=true} 时有意义(本次输出之前累计抑制条数)。 */
  public record Decision(boolean shouldLog, long suppressedSincePrevious) {}

  private static final class State {
    long nextAllowedAt; // nanoTime
    long suppressedCount;
  }
}
