package com.example.batch.orchestrator.application.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.orchestrator.config.OutboxProperties;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 2026-05-01 hardening:验证 {@link DefaultScheduleForwarder#computeNextRetryAt} 指数退避 + jitter 的边界 —
 * clamp 到 [base, max] 区间,默认参数下 5 次 attempt 时间序列符合预期。
 */
class DefaultScheduleForwarderRetryBackoffTest {

  @Test
  void shouldUseBaseDelayForFirstAttempt() {
    OutboxProperties props = props(60, 2.0, 600, 0.0); // jitter 关
    Instant before = Instant.now();
    Instant next = DefaultScheduleForwarder.computeNextRetryAt(1, props);
    long delaySec = Duration.between(before, next).toSeconds();
    assertThat(delaySec).isBetween(59L, 61L); // ≈ 60s
  }

  @Test
  void shouldExponentiallyBackoffWithoutJitter() {
    OutboxProperties props = props(60, 2.0, 1000, 0.0);
    // 验证 attempt 1..5 的 base × 2^(N-1) 序列(无 jitter,允许 ±1s clock drift)
    long[] expected = {60, 120, 240, 480, 960};
    for (int i = 0; i < expected.length; i++) {
      Instant before = Instant.now();
      Instant next = DefaultScheduleForwarder.computeNextRetryAt(i + 1, props);
      long actual = Duration.between(before, next).toSeconds();
      assertThat(actual)
          .as("attempt %d should backoff to ~%ds", i + 1, expected[i])
          .isBetween(expected[i] - 1, expected[i] + 1);
    }
  }

  @Test
  void shouldClampToMaxDelay() {
    OutboxProperties props = props(60, 2.0, 600, 0.0); // max 600s
    // attempt 6 = 60 × 2^5 = 1920s,被 clamp 到 600s
    Instant before = Instant.now();
    Instant next = DefaultScheduleForwarder.computeNextRetryAt(6, props);
    long delaySec = Duration.between(before, next).toSeconds();
    assertThat(delaySec).isBetween(599L, 601L);
  }

  @Test
  void shouldApplyJitterWithinBounds() {
    OutboxProperties props = props(100, 1.0, 100, 0.5); // 100s ± 50%
    // 跑 50 次,所有结果都应在 [50, 150]s 区间内,且不全相同(jitter 真在生效)
    Instant earliest = null;
    Instant latest = null;
    for (int i = 0; i < 50; i++) {
      Instant before = Instant.now();
      Instant next = DefaultScheduleForwarder.computeNextRetryAt(1, props);
      long delaySec = Duration.between(before, next).toSeconds();
      assertThat(delaySec).isBetween(49L, 151L);
      if (earliest == null || next.isBefore(earliest)) {
        earliest = next;
      }
      if (latest == null || next.isAfter(latest)) {
        latest = next;
      }
    }
    // 50 次抽样的最大 - 最小 应 > 10s(否则说明 jitter 没在抖)
    assertThat(Duration.between(earliest, latest).toSeconds()).isGreaterThan(10L);
  }

  @Test
  void shouldNeverGoBelowBase() {
    OutboxProperties props = props(60, 0.5, 600, 0.0); // 退避因子 < 1 应被强制为 ≥ 1
    Instant before = Instant.now();
    Instant next = DefaultScheduleForwarder.computeNextRetryAt(3, props);
    long delaySec = Duration.between(before, next).toSeconds();
    assertThat(delaySec).isGreaterThanOrEqualTo(59L); // 至少 base
  }

  @Test
  void shouldHandleAttemptZeroAsAttemptOne() {
    OutboxProperties props = props(60, 2.0, 600, 0.0);
    Instant before = Instant.now();
    Instant next = DefaultScheduleForwarder.computeNextRetryAt(0, props); // 边界:0 视作 1
    long delaySec = Duration.between(before, next).toSeconds();
    assertThat(delaySec).isBetween(59L, 61L);
  }

  // ── helpers ────────────────────────────────────────────────────────────

  private static OutboxProperties props(long base, double multiplier, long max, double jitter) {
    OutboxProperties p = new OutboxProperties();
    p.setRetryDelaySeconds(base);
    p.setRetryBackoffMultiplier(multiplier);
    p.setRetryMaxDelaySeconds(max);
    p.setRetryJitterRatio(jitter);
    return p;
  }
}
