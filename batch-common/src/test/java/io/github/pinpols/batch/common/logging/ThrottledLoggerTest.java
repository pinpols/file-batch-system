package io.github.pinpols.batch.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ThrottledLoggerTest {

  @Test
  void shouldEmit_whenFirstCallForKey() {
    AtomicLong clock = new AtomicLong(0L);
    ThrottledLogger logger = new ThrottledLogger(Duration.ofSeconds(10), clock::get);

    ThrottledLogger.Decision d = logger.evaluate("k1");

    assertThat(d.shouldLog()).isTrue();
    assertThat(d.suppressedSincePrevious()).isZero();
  }

  @Test
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
  void shouldTrackKeysIndependently() {
    AtomicLong clock = new AtomicLong(0L);
    ThrottledLogger logger = new ThrottledLogger(Duration.ofSeconds(10), clock::get);
    logger.evaluate("k1");

    ThrottledLogger.Decision d = logger.evaluate("k2");

    assertThat(d.shouldLog()).isTrue();
  }

  @Test
  void shouldEmit_whenKeyIsNull() {
    ThrottledLogger logger = new ThrottledLogger(Duration.ofSeconds(10));

    ThrottledLogger.Decision d = logger.evaluate(null);

    assertThat(d.shouldLog()).isTrue();
    assertThat(d.suppressedSincePrevious()).isZero();
  }

  @Test
  void shouldReject_whenCooldownNotPositive() {
    assertThatThrownBy(() -> new ThrottledLogger(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ThrottledLogger(Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ThrottledLogger(null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
