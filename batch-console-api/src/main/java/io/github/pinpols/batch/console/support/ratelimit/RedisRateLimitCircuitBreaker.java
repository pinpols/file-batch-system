package io.github.pinpols.batch.console.support.ratelimit;

import io.github.pinpols.batch.console.config.ConsoleRateLimitProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Redis 限流依赖的本地慢故障短路器。
 *
 * <p>限流在 Redis 故障时仍保持 fail-open，但连续故障后不再让每个请求同步等待 Redis。冷却期只允许一个
 * 请求探测恢复，其余请求直接放行。状态仅用于保护当前进程，不承担跨实例的安全决策。
 */
@Component("consoleRedisRateLimitCircuitBreaker")
public class RedisRateLimitCircuitBreaker {

  private final ConsoleRateLimitProperties properties;
  private final AtomicInteger consecutiveFailures = new AtomicInteger();
  private final AtomicLong openUntilMillis = new AtomicLong();
  private final AtomicBoolean probeInFlight = new AtomicBoolean();
  private final Counter failureCounter;
  private final Counter openCounter;

  public RedisRateLimitCircuitBreaker(
      ConsoleRateLimitProperties properties, ObjectProvider<MeterRegistry> meterRegistryProvider) {
    this(properties, meterRegistryProvider.getIfAvailable());
  }

  private RedisRateLimitCircuitBreaker(
      ConsoleRateLimitProperties properties, MeterRegistry registry) {
    this.properties = properties;
    this.failureCounter = registry == null
        ? null
        : Counter.builder("batch.console.rate_limit.redis.failure").register(registry);
    this.openCounter = registry == null
        ? null
        : Counter.builder("batch.console.rate_limit.redis.circuit.open").register(registry);
    if (registry != null) {
      registry.gauge(
          "batch.console.rate_limit.redis.circuit.opened",
          this,
          breaker -> breaker.isOpen() ? 1.0 : 0.0);
    }
  }

  /** 测试或无 Spring 容器场景使用的构造器，不注册指标。 */
  public static RedisRateLimitCircuitBreaker forTesting(ConsoleRateLimitProperties properties) {
    return new RedisRateLimitCircuitBreaker(properties, (MeterRegistry) null);
  }

  /** 当前请求是否可以访问 Redis；冷却期只放行一个探测请求。 */
  public boolean allowRedisCall() {
    long now = System.currentTimeMillis();
    long openUntil = openUntilMillis.get();
    if (openUntil == 0L) {
      return true;
    }
    if (now < openUntil) {
      return false;
    }
    return probeInFlight.compareAndSet(false, true);
  }

  public void recordSuccess() {
    consecutiveFailures.set(0);
    openUntilMillis.set(0L);
    probeInFlight.set(false);
  }

  public void recordFailure() {
    if (failureCounter != null) {
      failureCounter.increment();
    }
    int threshold = Math.max(1, properties.getRedisFailureThreshold());
    int failures = consecutiveFailures.incrementAndGet();
    if (failures >= threshold) {
      long cooldownMillis = Math.max(1L, properties.getRedisCircuitOpenSeconds()) * 1000L;
      long newOpenUntil = System.currentTimeMillis() + cooldownMillis;
      if (openUntilMillis.getAndSet(newOpenUntil) <= System.currentTimeMillis()
          && openCounter != null) {
        openCounter.increment();
      }
    }
    probeInFlight.set(false);
  }

  public boolean isOpen() {
    return openUntilMillis.get() > System.currentTimeMillis();
  }
}
