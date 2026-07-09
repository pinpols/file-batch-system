package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.worker.dispatchs.config.DispatchCircuitBreakerProperties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DispatchChannelCircuitBreakerTest {

  private static final String CHANNEL = "sftp-outbound";

  private DispatchCircuitBreakerProperties properties;
  private DispatchChannelCircuitBreaker circuitBreaker;

  @BeforeEach
  void setUp() {
    properties = new DispatchCircuitBreakerProperties();
    properties.setEnabled(true);
    properties.setFailureThreshold(3);
    properties.setCooldownMillis(60_000L);
    circuitBreaker = new DispatchChannelCircuitBreaker(properties);
  }

  @Test
  void shouldAllowWhenNoFailuresRecorded() {
    assertThat(circuitBreaker.allow(CHANNEL)).isTrue();
  }

  @Test
  void shouldAllowBelowFailureThreshold() {
    circuitBreaker.recordFailure(CHANNEL);
    circuitBreaker.recordFailure(CHANNEL);
    // threshold is 3, only 2 failures so far
    assertThat(circuitBreaker.allow(CHANNEL)).isTrue();
  }

  @Test
  void shouldOpenCircuitAtFailureThreshold() {
    circuitBreaker.recordFailure(CHANNEL);
    circuitBreaker.recordFailure(CHANNEL);
    circuitBreaker.recordFailure(CHANNEL); // reaches threshold
    assertThat(circuitBreaker.allow(CHANNEL)).isFalse();
  }

  @Test
  void shouldCountOpenCircuitsCorrectly() {
    triggerOpen("ch-1");
    triggerOpen("ch-2");
    assertThat(circuitBreaker.currentOpenCircuits()).isEqualTo(2);
  }

  @Test
  void shouldResetOnSuccess() {
    circuitBreaker.recordFailure(CHANNEL);
    circuitBreaker.recordFailure(CHANNEL);
    circuitBreaker.recordSuccess(CHANNEL);
    // failure counter reset; allow should return true
    assertThat(circuitBreaker.allow(CHANNEL)).isTrue();
    // new failures should not carry over the old count
    circuitBreaker.recordFailure(CHANNEL);
    circuitBreaker.recordFailure(CHANNEL);
    assertThat(circuitBreaker.allow(CHANNEL)).isTrue(); // still below threshold
  }

  @Test
  void shouldAllowAfterCooldownExpires() throws InterruptedException {
    // Use a very short cooldown for this test
    properties.setCooldownMillis(10L);
    DispatchChannelCircuitBreaker shortCooldown = new DispatchChannelCircuitBreaker(properties);

    triggerOpenWith(shortCooldown, CHANNEL);
    assertThat(shortCooldown.allow(CHANNEL)).isFalse();

    Thread.sleep(50); // wait for cooldown
    assertThat(shortCooldown.allow(CHANNEL)).isTrue();
    assertThat(shortCooldown.currentOpenCircuits()).isEqualTo(0);
  }

  @Test
  void shouldBypassCircuitBreakerWhenDisabled() {
    properties.setEnabled(false);
    DispatchChannelCircuitBreaker disabled = new DispatchChannelCircuitBreaker(properties);

    // Even after many failures, should always allow
    for (int i = 0; i < 10; i++) {
      disabled.recordFailure(CHANNEL);
    }
    assertThat(disabled.allow(CHANNEL)).isTrue();
    assertThat(disabled.currentOpenCircuits()).isEqualTo(0);
  }

  @Test
  void shouldIsolateFailuresByChannelKey() {
    triggerOpen("ch-bad");

    // a different channel is unaffected
    assertThat(circuitBreaker.allow("ch-good")).isTrue();
  }

  @Test
  void shouldIsolateOpenStateBetweenKeys() {
    triggerOpen("ch-bad");

    // ch-bad OPEN 时 ch-good 记录若干失败但未达阈值,仍独立放行且不计入 open 数
    circuitBreaker.recordFailure("ch-good");
    circuitBreaker.recordFailure("ch-good");
    assertThat(circuitBreaker.allow("ch-good")).isTrue();
    assertThat(circuitBreaker.currentOpenCircuits()).isEqualTo(1);
  }

  // --- 半开态(行为增强:原手写实现无半开态)---

  @Test
  void shouldLimitProbeCallsInHalfOpenState() throws InterruptedException {
    properties.setCooldownMillis(30L);
    DispatchChannelCircuitBreaker cb = new DispatchChannelCircuitBreaker(properties);
    triggerOpenWith(cb, CHANNEL);
    assertThat(cb.allow(CHANNEL)).isFalse(); // OPEN

    Thread.sleep(60); // 越过冷却期 → HALF_OPEN

    // HALF_OPEN 仅放行受限试探(3 个),之后未 record 前继续 allow 被拒绝
    assertThat(cb.allow(CHANNEL)).isTrue();
    assertThat(cb.allow(CHANNEL)).isTrue();
    assertThat(cb.allow(CHANNEL)).isTrue();
    assertThat(cb.allow(CHANNEL)).as("beyond half-open probe budget must be rejected").isFalse();
  }

  @Test
  void shouldCloseAfterSuccessfulHalfOpenProbes() throws InterruptedException {
    properties.setCooldownMillis(30L);
    DispatchChannelCircuitBreaker cb = new DispatchChannelCircuitBreaker(properties);
    triggerOpenWith(cb, CHANNEL);

    Thread.sleep(60); // → HALF_OPEN

    // 3 次试探全成功 → CLOSED
    for (int i = 0; i < 3; i++) {
      assertThat(cb.allow(CHANNEL)).isTrue();
      cb.recordSuccess(CHANNEL);
    }
    assertThat(cb.currentOpenCircuits()).isEqualTo(0);
    assertThat(cb.allow(CHANNEL)).isTrue(); // 已闭合,正常放行
  }

  @Test
  void shouldReopenWhenHalfOpenProbeFails() throws InterruptedException {
    properties.setCooldownMillis(30L);
    DispatchChannelCircuitBreaker cb = new DispatchChannelCircuitBreaker(properties);
    triggerOpenWith(cb, CHANNEL);

    Thread.sleep(60); // → HALF_OPEN

    // 试探失败(达半开失败阈值)→ 重新 OPEN
    for (int i = 0; i < 3; i++) {
      assertThat(cb.allow(CHANNEL)).isTrue();
      cb.recordFailure(CHANNEL);
    }
    assertThat(cb.allow(CHANNEL)).isFalse();
    assertThat(cb.currentOpenCircuits()).isEqualTo(1);
  }

  @Test
  void shouldOpenCircuitWhenFailuresArriveConcurrently() throws InterruptedException {
    // arrange: 高阈值 + 多线程并发累加，验证 compute 原子累加不丢计数、恰好达阈值即熔断。
    // 旧实现 incrementAndGet 后 remove 的 check-then-act 窗口在并发下会偶发丢失计数 → 熔断略延。
    int threshold = 200;
    properties.setFailureThreshold(threshold);
    DispatchChannelCircuitBreaker breaker = new DispatchChannelCircuitBreaker(properties);

    int threads = 16;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    int perThread = threshold / threads; // 16 * 12 = 192 < threshold，熔断前不能触发

    // act
    for (int t = 0; t < threads; t++) {
      pool.submit(
          () -> {
            try {
              start.await();
              for (int i = 0; i < perThread; i++) {
                breaker.recordFailure(CHANNEL);
              }
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              done.countDown();
            }
          });
    }
    start.countDown();
    assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
    pool.shutdownNow();

    // assert: 192 次失败（< 200 阈值）一次都不能丢，否则会提前熔断；此时仍允许通行
    assertThat(breaker.allow(CHANNEL)).isTrue();
    assertThat(breaker.currentOpenCircuits()).isEqualTo(0);

    // 再补满到阈值，必须恰好熔断
    for (int i = perThread * threads; i < threshold; i++) {
      breaker.recordFailure(CHANNEL);
    }
    assertThat(breaker.allow(CHANNEL)).isFalse();
    assertThat(breaker.currentOpenCircuits()).isEqualTo(1);
  }

  // --- helpers ---

  private void triggerOpen(String key) {
    triggerOpenWith(circuitBreaker, key);
  }

  private void triggerOpenWith(DispatchChannelCircuitBreaker cb, String key) {
    for (int i = 0; i < properties.getFailureThreshold(); i++) {
      cb.recordFailure(key);
    }
  }
}
