package io.github.pinpols.batch.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("限流日志判定:冷却窗口内的抑制与计数,键之间隔离,空键与非法冷却参数")
class ThrottledLoggerTest {

  @Test
  @DisplayName("某键首次调用:允许输出且此前没有被抑制记录")
  void shouldEmit_whenFirstCallForKey() {
    AtomicLong clock = new AtomicLong(0L);
    ThrottledLogger logger = new ThrottledLogger(Duration.ofSeconds(10), clock::get);

    ThrottledLogger.Decision d = logger.evaluate("k1");

    assertThat(d.shouldLog()).isTrue();
    assertThat(d.suppressedSincePrevious()).isZero();
  }

  @Test
  @DisplayName("冷却窗口内再次调用同一键:抑制输出且不累计被抑制数")
  void shouldSuppress_whenWithinCooldown() {
    AtomicLong clock = new AtomicLong(0L);
    ThrottledLogger logger = new ThrottledLogger(Duration.ofSeconds(10), clock::get);
    logger.evaluate("k1");

    clock.addAndGet(Duration.ofSeconds(3).toNanos());
    ThrottledLogger.Decision d = logger.evaluate("k1");

    assertThat(d.shouldLog()).isFalse();
    assertThat(d.suppressedSincePrevious()).isZero();
  }

  @Test
  @DisplayName("冷却窗口结束后再调用:恢复输出并带回窗口内被抑制的次数")
  void shouldEmitWithSuppressedCount_whenCooldownElapses() {
    AtomicLong clock = new AtomicLong(0L);
    ThrottledLogger logger = new ThrottledLogger(Duration.ofSeconds(10), clock::get);
    // First emit at t=0
    logger.evaluate("k1");
    // Two suppressed within window
    clock.addAndGet(Duration.ofSeconds(2).toNanos());
    logger.evaluate("k1");
    clock.addAndGet(Duration.ofSeconds(2).toNanos());
    logger.evaluate("k1");
    // Move past cooldown
    clock.addAndGet(Duration.ofSeconds(10).toNanos());

    ThrottledLogger.Decision d = logger.evaluate("k1");

    assertThat(d.shouldLog()).isTrue();
    assertThat(d.suppressedSincePrevious()).isEqualTo(2L);
  }

  @Test
  @DisplayName("不同键互不影响:另一键首次调用不受已限流键的冷却影响")
  void shouldTrackKeysIndependently() {
    AtomicLong clock = new AtomicLong(0L);
    ThrottledLogger logger = new ThrottledLogger(Duration.ofSeconds(10), clock::get);
    logger.evaluate("k1");

    ThrottledLogger.Decision d = logger.evaluate("k2");

    assertThat(d.shouldLog()).isTrue();
  }

  @Test
  @DisplayName("键为空时按独立键处理:首次调用允许输出")
  void shouldEmit_whenKeyIsNull() {
    ThrottledLogger logger = new ThrottledLogger(Duration.ofSeconds(10));

    ThrottledLogger.Decision d = logger.evaluate(null);

    assertThat(d.shouldLog()).isTrue();
    assertThat(d.suppressedSincePrevious()).isZero();
  }

  @Test
  @DisplayName("冷却时长非正或为空:构造即拒绝")
  void shouldReject_whenCooldownNotPositive() {
    assertThatThrownBy(() -> new ThrottledLogger(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ThrottledLogger(Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ThrottledLogger(null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
