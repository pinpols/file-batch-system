package io.github.pinpols.batch.common.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerMetricsAutoConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * 首次生产启用 resilience4j 的冒烟:验证 R4J CircuitBreaker autoconfig 在 SB4/JDK25 下能装配,并且 {@link
 * DownstreamFallback} 能从 autoconfig 提供的共享 {@link CircuitBreakerRegistry} 正常注入并跑通。
 *
 * <p>用 {@link ImportAutoConfiguration} 精确导 R4J 自家 autoconfig,绕开 batch-common 的
 * {@code @AutoConfiguration}(要 Clock / DataSource 等无关 bean),对齐 spike 的窄上下文做法。
 */
@SpringBootTest(classes = DownstreamFallbackContextSmokeTest.SmokeApp.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.main.web-application-type=none")
class DownstreamFallbackContextSmokeTest {

  @Autowired private DownstreamFallback downstreamFallback;
  @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;

  @Test
  void contextLoadsWithResilience4jAutoconfigAndFallbackWorks() {
    assertThat(circuitBreakerRegistry)
        .as("R4J autoconfig must provide CircuitBreakerRegistry")
        .isNotNull();
    assertThat(downstreamFallback).isNotNull();

    String result = downstreamFallback.callOrFallback("smoke", "op", () -> "ok", ex -> "fb");
    assertThat(result).isEqualTo("ok");
  }

  @SpringBootConfiguration
  @EnableConfigurationProperties(DownstreamCircuitBreakerProperties.class)
  @ImportAutoConfiguration({
    CircuitBreakerAutoConfiguration.class,
    CircuitBreakerMetricsAutoConfiguration.class,
  })
  static class SmokeApp {

    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }

    @Bean
    DownstreamFallback downstreamFallback(
        ObjectProvider<MeterRegistry> meterRegistryProvider,
        ObjectProvider<CircuitBreakerRegistry> circuitBreakerRegistryProvider,
        DownstreamCircuitBreakerProperties properties) {
      return new DownstreamFallback(
          meterRegistryProvider, circuitBreakerRegistryProvider, properties);
    }
  }
}
