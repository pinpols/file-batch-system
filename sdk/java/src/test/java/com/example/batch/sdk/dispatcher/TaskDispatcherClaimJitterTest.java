package com.example.batch.sdk.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * TOP #10:claimWithRetry 指数退避加 0-10% jitter,防 N 个 worker 同步雪崩 retry。
 *
 * <p>见 wire-protocol §C。
 */
class TaskDispatcherClaimJitterTest {

  @ParameterizedTest(name = "base={0}ms attempt={1} → delay ∈ [exp, exp*1.1)")
  @CsvSource({"200, 0", "200, 1", "200, 2", "200, 3", "500, 4"})
  @DisplayName("backoffWithJitter:delay ∈ [exponentialMs, exponentialMs + exponentialMs/10)")
  void shouldStayWithinJitterBand(long baseDelayMs, int attempt) {
    long exponentialMs = baseDelayMs << attempt;
    long upperExclusive = exponentialMs + Math.max(1L, exponentialMs / 10L);
    for (int i = 0; i < 200; i++) {
      long actual = TaskDispatcher.backoffWithJitter(baseDelayMs, attempt);
      assertThat(actual).isGreaterThanOrEqualTo(exponentialMs);
      assertThat(actual).isLessThan(upperExclusive);
    }
  }

  @Test
  @DisplayName("100 次同 attempt 的 delay 不全相等(jitter 真的生效)")
  void shouldProduceVariedDelays_acrossSamples() {
    Set<Long> seen = new HashSet<>();
    for (int i = 0; i < 100; i++) {
      seen.add(TaskDispatcher.backoffWithJitter(200L, 3)); // exp=1600,jitter [0,160)
    }
    // 100 次抽样,jitter 范围 [0,160) → 几乎不可能全相等;>1 即证 jitter 生效
    assertThat(seen).hasSizeGreaterThan(1);
  }

  @Test
  @DisplayName("baseDelayMs=0 → 立即返回 0(无 jitter,无 sleep)")
  void shouldReturnZero_whenBaseDelayIsZero() {
    assertThat(TaskDispatcher.backoffWithJitter(0L, 5)).isZero();
  }

  @Test
  @DisplayName("attempt 巨大不应 overflow(safeAttempt 限 30)")
  void shouldNotOverflow_whenAttemptHuge() {
    // attempt=100 被钳到 30,200ms << 30 仍是有限大 long
    long actual = TaskDispatcher.backoffWithJitter(200L, 100);
    long expectedExp = 200L << 30;
    assertThat(actual).isGreaterThanOrEqualTo(expectedExp);
    assertThat(actual).isLessThan(expectedExp + Math.max(1L, expectedExp / 10L));
  }
}
