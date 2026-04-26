package com.example.batch.trigger.wheel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * CatchUpThrottle 算法测试 — 用 stub Clock,毫秒级跑完(无真 sleep)。
 *
 * <p>验证场景:
 *
 * <ul>
 *   <li>初始首个 acquire 立即放行(0 等待)
 *   <li>后续 acquire 严格按 interval 等待
 *   <li>1s 空档不累积 burst(无突发,关键差异 vs Guava)
 *   <li>非法 ratePerSecond 抛 IAE
 * </ul>
 */
class CatchUpThrottleTest {

  /** 简单 stub clock:millis 单调推进,test 主动 advance。 */
  static class StubClock extends Clock {
    private long millis;

    StubClock(long initial) {
      this.millis = initial;
    }

    void advanceMillis(long delta) {
      this.millis += delta;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(millis);
    }

    @Override
    public long millis() {
      return millis;
    }
  }

  @Test
  void firstAcquireIsImmediate() {
    StubClock clock = new StubClock(1_000_000L);
    CatchUpThrottle throttle = new CatchUpThrottle(10.0, clock);

    long wait = throttle.reserveSlot();
    assertThat(wait).isZero();
  }

  @Test
  void secondAcquireWaitsByInterval() {
    StubClock clock = new StubClock(1_000_000L);
    CatchUpThrottle throttle = new CatchUpThrottle(10.0, clock); // 100ms 间隔

    throttle.reserveSlot(); // 第 1 个,0 等待
    long wait = throttle.reserveSlot(); // 第 2 个,需等 100ms
    assertThat(wait).isEqualTo(100);
  }

  @Test
  void backToBackAcquiresAccumulateInterval() {
    StubClock clock = new StubClock(0L);
    CatchUpThrottle throttle = new CatchUpThrottle(10.0, clock);

    long wait1 = throttle.reserveSlot();
    long wait2 = throttle.reserveSlot();
    long wait3 = throttle.reserveSlot();
    long wait4 = throttle.reserveSlot();

    // 第 1 个立即放行;后续每个累加 100ms
    assertThat(wait1).isZero();
    assertThat(wait2).isEqualTo(100);
    assertThat(wait3).isEqualTo(200);
    assertThat(wait4).isEqualTo(300);
  }

  @Test
  void idleGapDoesNotAccumulateBurst() {
    StubClock clock = new StubClock(0L);
    CatchUpThrottle throttle = new CatchUpThrottle(10.0, clock); // 100ms 间隔

    throttle.reserveSlot(); // 第 1 个 @ t=0
    clock.advanceMillis(10_000L); // 空档 10 秒
    long wait = throttle.reserveSlot(); // 第 2 个 @ t=10000

    // 空档不累积 token,本次直接放行
    assertThat(wait).isZero();

    // 后续 acquire 仍按 100ms 走,不会"还人情"批量放行
    long wait2 = throttle.reserveSlot();
    assertThat(wait2).isEqualTo(100);
  }

  @Test
  void clockAdvanceReducesWait() {
    StubClock clock = new StubClock(0L);
    CatchUpThrottle throttle = new CatchUpThrottle(10.0, clock);

    throttle.reserveSlot();
    clock.advanceMillis(50); // 物理时间过了 50ms

    // 应该还需要等 50ms 才能放行下一个(100ms - 50ms 已过)
    long wait = throttle.reserveSlot();
    assertThat(wait).isEqualTo(50);
  }

  @Test
  void ratePerSecondMustBePositive() {
    assertThatThrownBy(() -> new CatchUpThrottle(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ratePerSecond must be > 0");
    assertThatThrownBy(() -> new CatchUpThrottle(-1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void highRateGivesShortInterval() {
    StubClock clock = new StubClock(0L);
    CatchUpThrottle throttle = new CatchUpThrottle(1000.0, clock); // 1ms 间隔

    throttle.reserveSlot();
    long wait = throttle.reserveSlot();
    assertThat(wait).isEqualTo(1);
  }

  @Test
  void lowRateGivesLongInterval() {
    StubClock clock = new StubClock(0L);
    CatchUpThrottle throttle = new CatchUpThrottle(0.5, clock); // 2 秒间隔

    throttle.reserveSlot();
    long wait = throttle.reserveSlot();
    assertThat(wait).isEqualTo(2000);
  }
}
