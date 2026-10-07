package io.github.pinpols.batch.console.support.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.config.ConsoleRateLimitProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Redis 限流熔断器: 连续失败开启与成功后恢复")
class RedisRateLimitCircuitBreakerTest {

  @Test
  @DisplayName("连续失败达到阈值后熔断开启并拒绝调用, 记录一次成功后恢复放行")
  void shouldOpenOnConsecutiveFailuresAndRecoverOnSuccess_whenThresholdReached() {
    ConsoleRateLimitProperties properties = new ConsoleRateLimitProperties();
    properties.setRedisFailureThreshold(2);
    RedisRateLimitCircuitBreaker circuit = RedisRateLimitCircuitBreaker.forTesting(properties);

    assertThat(circuit.allowRedisCall()).isTrue();
    circuit.recordFailure();
    assertThat(circuit.allowRedisCall()).isTrue();
    circuit.recordFailure();

    assertThat(circuit.isOpen()).isTrue();
    assertThat(circuit.allowRedisCall()).isFalse();

    circuit.recordSuccess();
    assertThat(circuit.isOpen()).isFalse();
    assertThat(circuit.allowRedisCall()).isTrue();
  }
}
