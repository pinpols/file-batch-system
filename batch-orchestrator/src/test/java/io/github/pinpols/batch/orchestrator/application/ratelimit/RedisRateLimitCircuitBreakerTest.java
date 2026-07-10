package io.github.pinpols.batch.orchestrator.application.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.github.pinpols.batch.orchestrator.config.RateLimitProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.lettuce.core.RedisCommandTimeoutException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Redis 短路熔断行为测试（用 mock {@link LettuceBasedProxyManager} 确定性驱动状态机，不依赖真 Redis）。
 *
 * <p>守护三件事：
 *
 * <ul>
 *   <li>连续 N 次 Redis 故障 → 熔断 OPEN → 后续请求 <b>不再发 Redis 命令</b>（verify getProxy 调用次数封顶）直接 fail-open，省掉
 *       requestTimeout 阻塞，方向仍是放行；
 *   <li>OPEN 窗口到期 → HALF_OPEN 探测成功 → CLOSED 恢复正常限流；
 *   <li>Redis 健康时熔断不触发，限流正常（拒绝不计失败、不误熔断）。
 * </ul>
 */
class RedisRateLimitCircuitBreakerTest {

  private static RateLimitProperties.CircuitBreaker config(
      int consecutiveFailures, long openWindowMillis) {
    RateLimitProperties.CircuitBreaker config = new RateLimitProperties.CircuitBreaker();
    config.setEnabled(true);
    config.setConsecutiveFailures(consecutiveFailures);
    config.setOpenWindowMillis(openWindowMillis);
    config.setHalfOpenProbes(1);
    return config;
  }

  @Test
  @DisplayName("连续 N 次 Redis 故障 → 熔断 OPEN → 后续请求短路 fail-open 且不再发 Redis 命令(无 500ms 阻塞)")
  void sustainedFailureOpensCircuitThenShortCircuitsWithoutHittingRedis() {
    // arrange: getProxy 恒抛 RedisException(模拟慢故障命令级失败)
    @SuppressWarnings("unchecked")
    LettuceBasedProxyManager<String> proxyManager = mock(LettuceBasedProxyManager.class);
    when(proxyManager.getProxy(anyString(), any()))
        .thenThrow(new RedisCommandTimeoutException("simulated sustained timeout"));
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    RedisRateLimitCircuitBreaker circuitBreaker =
        new RedisRateLimitCircuitBreaker(
            config(3, 10_000L), CircuitBreakerRegistry.ofDefaults(), meterRegistry);
    TokenBucketRateLimiter limiter =
        new TokenBucketRateLimiter(proxyManager, meterRegistry, circuitBreaker);

    // act: 3 次故障填满窗口 → 熔断 OPEN(3 次都真发命令、都 fail-open)
    for (int i = 0; i < 3; i++) {
      assertThat(limiter.tryConsume("t1", "TASK_CLAIM", 100))
          .as("failing call %d must fail-open", i + 1)
          .isTrue();
    }
    // 第 4 次:熔断已 OPEN,应短路——不发 Redis 命令、快速 fail-open
    long startNanos = System.nanoTime();
    boolean allowed = limiter.tryConsume("t1", "TASK_CLAIM", 100);
    long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

    // assert
    assertThat(allowed).as("open circuit must still fail-open (allow)").isTrue();
    // 关键:getProxy 只被前 3 次调用,第 4 次被熔断短路——证明没再发 Redis 命令(慢故障下即省掉 requestTimeout 阻塞)
    verify(proxyManager, times(3)).getProxy(anyString(), any());
    assertThat(elapsedMillis).as("short-circuit must be near-instant").isLessThan(100L);
    // gauge=1(OPEN)
    assertThat(meterRegistry.get(RedisRateLimitCircuitBreaker.METRIC_CIRCUIT_OPEN).gauge().value())
        .isEqualTo(1.0);
    // 短路放行计 reason=circuit_open;前 3 次发了命令的故障计 reason=redis_exception
    assertThat(
            meterRegistry
                .get(TokenBucketRateLimiter.METRIC_FAILOPEN)
                .tag("reason", TokenBucketRateLimiter.FAILOPEN_REASON_CIRCUIT_OPEN)
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(
            meterRegistry
                .get(TokenBucketRateLimiter.METRIC_FAILOPEN)
                .tag("reason", TokenBucketRateLimiter.FAILOPEN_REASON_REDIS)
                .counter()
                .count())
        .isEqualTo(3.0);
  }

  @Test
  @DisplayName("OPEN 窗口到期 → HALF_OPEN 探测成功 → CLOSED 恢复正常限流")
  void halfOpenProbeSuccessRecoversToClosed() throws InterruptedException {
    // arrange: fail=true 时 getProxy 抛错;fail=false 时返回放行的 BucketProxy
    AtomicBoolean fail = new AtomicBoolean(true);
    BucketProxy bucketProxy = mock(BucketProxy.class);
    when(bucketProxy.tryConsume(1)).thenReturn(true);
    @SuppressWarnings("unchecked")
    LettuceBasedProxyManager<String> proxyManager = mock(LettuceBasedProxyManager.class);
    when(proxyManager.getProxy(anyString(), any()))
        .thenAnswer(
            invocation -> {
              if (fail.get()) {
                throw new RedisCommandTimeoutException("simulated timeout");
              }
              return bucketProxy;
            });
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    RedisRateLimitCircuitBreaker circuitBreaker =
        new RedisRateLimitCircuitBreaker(
            config(3, 100L), CircuitBreakerRegistry.ofDefaults(), meterRegistry);
    TokenBucketRateLimiter limiter =
        new TokenBucketRateLimiter(proxyManager, meterRegistry, circuitBreaker);

    // act 1: 打开熔断
    for (int i = 0; i < 3; i++) {
      limiter.tryConsume("t1", "LAUNCH", 100);
    }
    assertThat(meterRegistry.get(RedisRateLimitCircuitBreaker.METRIC_CIRCUIT_OPEN).gauge().value())
        .as("circuit should be OPEN after sustained failure")
        .isEqualTo(1.0);

    // act 2: Redis 恢复健康,等 OPEN 窗口(100ms)到期后发探测请求
    fail.set(false);
    Thread.sleep(180L);
    // HALF_OPEN 探测:真发 Redis 命令且成功 → CLOSED
    boolean probeAllowed = limiter.tryConsume("t1", "LAUNCH", 100);

    // assert: 探测成功放行,熔断回到 CLOSED(gauge=0),后续正常发 Redis 命令
    assertThat(probeAllowed).isTrue();
    assertThat(meterRegistry.get(RedisRateLimitCircuitBreaker.METRIC_CIRCUIT_OPEN).gauge().value())
        .as("circuit should recover to CLOSED after successful half-open probe")
        .isEqualTo(0.0);
    assertThat(limiter.tryConsume("t1", "LAUNCH", 100))
        .as("normal limiting resumes after recovery")
        .isTrue();
  }

  @Test
  @DisplayName("Redis 健康时熔断不触发,限流正常工作(拒绝不计失败,不误熔断)")
  void healthyRedisDoesNotTripCircuitAndLimitingWorks() {
    // arrange: getProxy 返回 BucketProxy;前 2 次放行,之后拒绝(桶耗尽)
    BucketProxy bucketProxy = mock(BucketProxy.class);
    when(bucketProxy.tryConsume(1)).thenReturn(true, true, false, false, false);
    @SuppressWarnings("unchecked")
    LettuceBasedProxyManager<String> proxyManager = mock(LettuceBasedProxyManager.class);
    when(proxyManager.getProxy(eq("ratelimit:t1:TASK_REPORT"), any())).thenReturn(bucketProxy);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    RedisRateLimitCircuitBreaker circuitBreaker =
        new RedisRateLimitCircuitBreaker(
            config(3, 10_000L), CircuitBreakerRegistry.ofDefaults(), meterRegistry);
    TokenBucketRateLimiter limiter =
        new TokenBucketRateLimiter(proxyManager, meterRegistry, circuitBreaker);

    // act + assert: 放行两次,随后连续拒绝(限流正常);拒绝是正常返回 false,不是异常 → 不计失败
    assertThat(limiter.tryConsume("t1", "TASK_REPORT", 2)).isTrue();
    assertThat(limiter.tryConsume("t1", "TASK_REPORT", 2)).isTrue();
    for (int i = 0; i < 3; i++) {
      assertThat(limiter.tryConsume("t1", "TASK_REPORT", 2))
          .as("bucket drained → reject %d", i + 1)
          .isFalse();
    }

    // 熔断从未打开(gauge=0),getProxy 每次都真发(未短路),无任何 fail-open 计数
    assertThat(meterRegistry.get(RedisRateLimitCircuitBreaker.METRIC_CIRCUIT_OPEN).gauge().value())
        .isEqualTo(0.0);
    verify(proxyManager, times(5)).getProxy(anyString(), any());
    assertThat(meterRegistry.find(TokenBucketRateLimiter.METRIC_FAILOPEN).counter()).isNull();
  }
}
