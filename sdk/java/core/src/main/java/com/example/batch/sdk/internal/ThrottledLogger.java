package com.example.batch.sdk.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;

/**
 * SDK 内部高频日志压制器 — Lane J:在 {@code throttleWindow} 内,同 {@code key} 只输出第一条,后续被抑制并计数, 下一次窗外放行时附 {@code
 * (suppressed N times since ...)} 提示,运维既不被刷屏也不会丢线索。
 *
 * <p>非 public:仅供 SDK 内部 dispatcher / scheduler / consumer 等高频路径使用,不暴露给租户业务代码。
 *
 * <p>线程安全:状态用 {@link ConcurrentHashMap} + {@link AtomicLong},compute 原子判定窗口归位 / 抑制计数, 高并发同 key
 * 不会丢日志也不会重复 "first" 输出。
 */
public final class ThrottledLogger {

  private final Logger delegate;
  private final Duration throttleWindow;
  private final Map<String, State> states = new ConcurrentHashMap<>();

  private ThrottledLogger(Logger delegate, Duration throttleWindow) {
    if (delegate == null) throw new IllegalArgumentException("delegate logger required");
    if (throttleWindow == null || throttleWindow.isNegative() || throttleWindow.isZero()) {
      throw new IllegalArgumentException("throttleWindow must be positive");
    }
    this.delegate = delegate;
    this.throttleWindow = throttleWindow;
  }

  public static ThrottledLogger create(Logger delegate, Duration throttleWindow) {
    return new ThrottledLogger(delegate, throttleWindow);
  }

  /** 按 key 节流的 INFO。同 key 在窗口内首条放行,其余压制(只累加计数,下一次放行时附 suppressed 提示)。 */
  public void info(String key, String format, Object... args) {
    if (delegate.isInfoEnabled()) {
      log(Level.INFO, key, format, args);
    }
  }

  public void warn(String key, String format, Object... args) {
    if (delegate.isWarnEnabled()) {
      log(Level.WARN, key, format, args);
    }
  }

  public void error(String key, String format, Object... args) {
    if (delegate.isErrorEnabled()) {
      log(Level.ERROR, key, format, args);
    }
  }

  private void log(Level level, String key, String format, Object... args) {
    Instant now = Instant.now();
    long windowMillis = throttleWindow.toMillis();
    long[] decisionHolder = new long[] {-1L}; // [0] = -1 抑制,≥0 放行(数值为本轮 suppressed 计数)
    states.compute(
        key,
        (k, state) -> {
          if (state == null) {
            State fresh = new State();
            fresh.lastEmittedEpochMillis = now.toEpochMilli();
            decisionHolder[0] = 0L; // 首次放行,无 suppressed
            return fresh;
          }
          long elapsed = now.toEpochMilli() - state.lastEmittedEpochMillis;
          if (elapsed >= windowMillis) {
            decisionHolder[0] = state.suppressed.getAndSet(0L);
            state.lastEmittedEpochMillis = now.toEpochMilli();
          } else {
            state.suppressed.incrementAndGet();
            decisionHolder[0] = -1L;
          }
          return state;
        });
    long suppressed = decisionHolder[0];
    if (suppressed < 0) {
      // 被抑制 — 不输出,仅累加计数
      return;
    }
    String finalFormat = format;
    Object[] finalArgs = args;
    if (suppressed > 0) {
      finalFormat = format + " (suppressed {} similar log(s) in last {})";
      finalArgs = appendArgs(args, suppressed, throttleWindow);
    }
    switch (level) {
      case INFO -> delegate.info(finalFormat, finalArgs);
      case WARN -> delegate.warn(finalFormat, finalArgs);
      case ERROR -> delegate.error(finalFormat, finalArgs);
    }
  }

  private static Object[] appendArgs(Object[] base, Object... extras) {
    Object[] merged = new Object[base.length + extras.length];
    System.arraycopy(base, 0, merged, 0, base.length);
    System.arraycopy(extras, 0, merged, base.length, extras.length);
    return merged;
  }

  private enum Level {
    INFO,
    WARN,
    ERROR
  }

  /** 每 key 一份的状态。决策结果走 compute 闭包外的局部 holder 传出,避免跨线程读写。 */
  private static final class State {
    long lastEmittedEpochMillis;
    final AtomicLong suppressed = new AtomicLong(0L);
  }
}
