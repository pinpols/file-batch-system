package io.github.pinpols.batch.console.support.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.config.ConsoleRateLimitProperties;
import org.junit.jupiter.api.Test;

class RedisRateLimitCircuitBreakerTest {

  @Test
  void opensAfterConsecutiveFailuresAndAllowsOnlyAfterSuccess() {
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
