package io.github.pinpols.batch.console.support.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.pinpols.batch.console.config.ConsoleRateLimitProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@DisplayName("Redis 限流熔断器: 连续失败开启、冷却期单探针与成功后恢复")
class RedisRateLimitCircuitBreakerTest {

    @Test
    @DisplayName("失败次数未达阈值: 保持关闭并放行")
    void shouldStayClosed_whenFailuresBelowThreshold() {
        RedisRateLimitCircuitBreaker circuit = newCircuit(2, 15, CircuitBreakerRegistry.ofDefaults());

        circuit.recordFailure();

        assertThat(circuit.isOpen()).isFalse();
        assertThat(circuit.allowRedisCall()).isTrue();
    }

    @Test
    @DisplayName("连续失败达到阈值: 打开并拒绝后续调用")
    void shouldOpenAndReject_whenThresholdReached() {
        RedisRateLimitCircuitBreaker circuit = newCircuit(2, 15, CircuitBreakerRegistry.ofDefaults());

        circuit.recordFailure();
        circuit.recordFailure();

        assertThat(circuit.isOpen()).isTrue();
        assertThat(circuit.allowRedisCall()).isFalse();
    }

    @Test
    @DisplayName("冷却期结束: 只放行一个探测请求，其余继续拒绝")
    void shouldPermitSingleProbe_whenHalfOpen() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        RedisRateLimitCircuitBreaker circuit = newCircuit(1, 15, registry);
        circuit.recordFailure();
        assertThat(circuit.allowRedisCall()).isFalse();

        registry.circuitBreaker(RedisRateLimitCircuitBreaker.CIRCUIT_NAME).transitionToHalfOpenState();

        assertThat(circuit.allowRedisCall()).isTrue();
        assertThat(circuit.allowRedisCall()).isFalse();
    }

    @Test
    @DisplayName("探测成功: 关闭熔断并恢复正常放行")
    void shouldClose_whenProbeSucceeds() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        RedisRateLimitCircuitBreaker circuit = newCircuit(1, 15, registry);
        circuit.recordFailure();
        registry.circuitBreaker(RedisRateLimitCircuitBreaker.CIRCUIT_NAME).transitionToHalfOpenState();
        assertThat(circuit.allowRedisCall()).isTrue();

        circuit.recordSuccess();

        assertThat(circuit.isOpen()).isFalse();
        assertThat(circuit.allowRedisCall()).isTrue();
    }

    @Test
    @DisplayName("探测失败: 重新打开熔断")
    void shouldReopen_whenProbeFails() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        RedisRateLimitCircuitBreaker circuit = newCircuit(1, 15, registry);
        circuit.recordFailure();
        registry.circuitBreaker(RedisRateLimitCircuitBreaker.CIRCUIT_NAME).transitionToHalfOpenState();
        assertThat(circuit.allowRedisCall()).isTrue();

        circuit.recordFailure();

        assertThat(circuit.isOpen()).isTrue();
        assertThat(circuit.allowRedisCall()).isFalse();
    }

    @Test
    @DisplayName("指标: 失败计数、打开次数与状态 gauge 沿用原名称")
    void shouldExposeLegacyMetricNames() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        RedisRateLimitCircuitBreaker circuit = new RedisRateLimitCircuitBreaker(properties(1, 15), registry, meters);

        circuit.recordFailure();

        assertThat(meters.get("batch.console.rate_limit.redis.failure")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(meters.get("batch.console.rate_limit.redis.circuit.open")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(meters.get("batch.console.rate_limit.redis.circuit.opened")
                        .gauge()
                        .value())
                .isEqualTo(1.0);
        assertThat(registry.circuitBreaker(RedisRateLimitCircuitBreaker.CIRCUIT_NAME)
                        .getState())
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    private static RedisRateLimitCircuitBreaker newCircuit(
            int failureThreshold, int openSeconds, CircuitBreakerRegistry registry) {
        return RedisRateLimitCircuitBreaker.forTesting(properties(failureThreshold, openSeconds), registry);
    }

    private static ConsoleRateLimitProperties properties(int failureThreshold, int openSeconds) {
        ConsoleRateLimitProperties properties = new ConsoleRateLimitProperties();
        properties.setRedisFailureThreshold(failureThreshold);
        properties.setRedisCircuitOpenSeconds(openSeconds);
        return properties;
    }
}
