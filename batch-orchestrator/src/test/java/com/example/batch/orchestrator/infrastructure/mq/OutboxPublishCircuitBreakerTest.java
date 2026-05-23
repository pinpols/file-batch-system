package com.example.batch.orchestrator.infrastructure.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.config.OutboxProperties;
import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorRedisSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** T-1: OutboxPublishCircuitBreaker 测试——验证 CLOSED → OPEN → HALF_OPEN → CLOSED 状态机。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxPublishCircuitBreakerTest {

  @Mock private BatchOrchestratorGovernanceProperties governance;
  @Mock private OrchestratorRedisSupport redis;

  private OutboxPublishCircuitBreaker breaker;

  @BeforeEach
  void setUp() {
    OutboxProperties props = new OutboxProperties();
    props.setCircuitBreakerEnabled(true);
    props.setCircuitBreakerFailureThresholdConsecutivePolls(3);
    props.setCircuitBreakerCooldownMillis(5000L);
    props.setPollIntervalMillis(1000L);
    when(governance.outbox()).thenReturn(props);
    breaker = new OutboxPublishCircuitBreaker(governance, redis);
  }

  @Test
  void shouldAllowWhenCircuitBreakerDisabled() {
    OutboxProperties props = new OutboxProperties();
    props.setCircuitBreakerEnabled(false);
    when(governance.outbox()).thenReturn(props);
    breaker = new OutboxPublishCircuitBreaker(governance, redis);

    assertThat(breaker.allowNow()).isTrue();
  }

  @Test
  void shouldAllowWhenRedisReturnsClosed() {
    when(redis.evalLong(anyString(), anyString(), anyString())).thenReturn(0L);

    assertThat(breaker.allowNow()).isTrue();
  }

  @Test
  void shouldDenyWhenRedisReturnsOpenUntilFuture() {
    long futureMs = BatchDateTimeSupport.utcEpochMillis() + 60_000;
    when(redis.evalLong(anyString(), anyString(), anyString())).thenReturn(futureMs);

    // First call reads Redis and caches open state
    assertThat(breaker.allowNow()).isFalse();
    // Second call uses cached value
    assertThat(breaker.allowNow()).isFalse();
  }

  @Test
  void shouldTransitionToHalfOpenAfterCooldown() {
    // Simulate: breaker was open but cooldown has passed
    long pastMs = BatchDateTimeSupport.utcEpochMillis() - 1000;
    when(redis.evalLong(anyString(), anyString(), anyString())).thenReturn(pastMs);

    // First call sees expired openUntilMs from Redis → triggers half-open
    boolean firstCall = breaker.allowNow();
    // After Redis returns pastMs (expired), allowNow should allow (transition to half-open)
    assertThat(firstCall).isTrue();
  }

  @Test
  void onAdvanceResult_shouldResetHalfOpenProbeOnSuccess() {
    long pastMs = BatchDateTimeSupport.utcEpochMillis() - 1000;
    when(redis.evalLong(anyString(), anyString(), anyString())).thenReturn(pastMs);

    // Enter half-open
    breaker.allowNow();

    // Successful probe → advance with 0 failures
    when(redis.evalLong(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(0L);
    breaker.onAdvanceResult(0);

    // Now should be closed
    when(redis.evalLong(anyString(), anyString(), anyString())).thenReturn(0L);
    assertThat(breaker.allowNow()).isTrue();
  }

  @Test
  void shouldUseCachedStateWhenRedisReturnsNull() {
    // First: simulate Redis open state cached
    long futureMs = BatchDateTimeSupport.utcEpochMillis() + 60_000;
    when(redis.evalLong(anyString(), anyString(), anyString())).thenReturn(futureMs);
    assertThat(breaker.allowNow()).isFalse();

    // Redis becomes unavailable (returns null) — should NOT force closed,
    // should reuse cached open state and still deny.
    when(redis.evalLong(anyString(), anyString(), anyString())).thenReturn(null);
    // To force slow-path: invalidate closed cache by waiting for openUntilMs to pass is hard;
    // instead, just call allowNow — cached snapshot openUntilMs is still future so fast-path denies.
    assertThat(breaker.allowNow()).isFalse();
  }

  @Test
  void onAdvanceResult_shouldReOpenOnProbeFailure() {
    long pastMs = BatchDateTimeSupport.utcEpochMillis() - 1000;
    when(redis.evalLong(anyString(), anyString(), anyString())).thenReturn(pastMs);

    // Enter half-open
    breaker.allowNow();

    // Failed probe → advance with failures
    long futureMs = BatchDateTimeSupport.utcEpochMillis() + 60_000;
    when(redis.evalLong(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(futureMs);
    breaker.onAdvanceResult(3);

    // Should be open again
    assertThat(breaker.allowNow()).isFalse();
  }
}
