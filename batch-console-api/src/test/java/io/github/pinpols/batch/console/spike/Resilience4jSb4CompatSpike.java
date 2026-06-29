package io.github.pinpols.batch.console.spike;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerMetricsAutoConfiguration;
import io.github.resilience4j.springboot3.retry.autoconfigure.RetryAutoConfiguration;
import io.github.resilience4j.springboot3.retry.autoconfigure.RetryMetricsAutoConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * SPIKE:Resilience4j 2.3.0(targets Spring Boot 3 / Spring Framework 6)在 Spring Boot 4.x (Spring
 * Framework 7)+ JDK 21 基线上的兼容验证。
 *
 * <p>验收点(对齐 docs/runbook/downstream-degradation.md 升级清单):
 *
 * <ol>
 *   <li>R4J 各 autoconfig 在 SB4 上能加载(不报 NoSuchMethod / NoClassDef / IncompatibleClassChange)
 *   <li>{@link CircuitBreakerRegistry} / {@link RetryRegistry} bean 注册
 *   <li>application 属性配置的 instance 被读到
 *   <li>状态机失败累计 → OPEN
 *   <li>Micrometer 自动埋点出现(挑 {@code resilience4j.circuitbreaker.*})
 * </ol>
 *
 * <p>用 {@link ImportAutoConfiguration} 只导 R4J 自家的 autoconfig,绕开本项目 batch-common 的
 * {@code @AutoConfiguration}(它要 Clock / InformationSchemaMapper / DataSource 等本 spike 不需要的依赖)。
 *
 * <p>未验(独立 PR):{@code @CircuitBreaker} 注解切面在 RestClient 路径命中(需 Web 上下文 + AOP)。
 */
@SpringBootTest(classes = Resilience4jSb4CompatSpike.SpikeApp.class)
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      "resilience4j.circuitbreaker.instances.test.failure-rate-threshold=50",
      "resilience4j.circuitbreaker.instances.test.sliding-window-size=4",
      "resilience4j.circuitbreaker.instances.test.minimum-number-of-calls=2",
      "resilience4j.circuitbreaker.instances.test.wait-duration-in-open-state=10s",
      "resilience4j.retry.instances.test.max-attempts=3",
      "spring.main.web-application-type=none",
    })
class Resilience4jSb4CompatSpike {

  @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;
  @Autowired private RetryRegistry retryRegistry;
  @Autowired private MeterRegistry meterRegistry;

  @Test
  void shouldLoadCircuitBreakerInstanceFromProperties() {
    CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("test");
    assertThat(cb.getCircuitBreakerConfig().getFailureRateThreshold()).isEqualTo(50f);
    assertThat(cb.getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(4);
  }

  @Test
  void shouldLoadRetryInstanceFromProperties() {
    assertThat(retryRegistry.retry("test").getRetryConfig().getMaxAttempts()).isEqualTo(3);
  }

  @Test
  void shouldTripCircuitBreakerOpenAfterRepeatedFailures() {
    CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("test");
    for (int i = 0; i < 4; i++) {
      try {
        cb.executeSupplier(
            () -> {
              throw new RuntimeException("forced");
            });
      } catch (RuntimeException ignored) {
        // 符合预期
      }
    }
    assertThat(cb.getState())
        .as("4 forced failures with 50%% threshold + window=4 should trip OPEN")
        .isEqualTo(CircuitBreaker.State.OPEN);
  }

  @Test
  void shouldExposeMicrometerCounter() {
    CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("test");
    cb.executeSupplier(() -> "ok");
    boolean found =
        meterRegistry.getMeters().stream()
            .anyMatch(m -> m.getId().getName().startsWith("resilience4j.circuitbreaker"));
    assertThat(found).as("micrometer bridge should auto-register counters").isTrue();
  }

  @SpringBootConfiguration
  @ImportAutoConfiguration({
    CircuitBreakerAutoConfiguration.class,
    CircuitBreakerMetricsAutoConfiguration.class,
    RetryAutoConfiguration.class,
    RetryMetricsAutoConfiguration.class,
  })
  static class SpikeApp {
    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }
}
