package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.worker.dispatchs.config.DispatchCircuitBreakerProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerMetricsAutoConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * worker-dispatch 首次生产启用 resilience4j 的冒烟:验证 R4J CircuitBreaker autoconfig 在本模块 SB4/JDK25 上下文下能装配,且
 * {@link DispatchChannelCircuitBreaker} 能正常建 bean 并跑通门面方法。
 *
 * <p>{@link DispatchChannelCircuitBreaker} 使用自持的 per-key registry(不依赖 autoconfig 的共享 registry, 以隔离
 * {@code currentOpenCircuits()} 语义),但本冒烟仍导入 R4J autoconfig 以确认其在本模块可无害装配。
 *
 * <p><b>#783 B3 覆盖缺口已补齐</b>:{@link #circuitBreakerMetricsBindsStateMeter_selfHeldRegistry()} 断言自持
 * registry 的 per-key breaker OPEN 时,注入的 {@link MeterRegistry} 里也真出现对应的 {@code
 * resilience4j.circuitbreaker.state} meter——不再只覆盖共享 autoconfig registry。
 */
@SpringBootTest(classes = DispatchChannelCircuitBreakerContextSmokeTest.SmokeApp.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.main.web-application-type=none")
class DispatchChannelCircuitBreakerContextSmokeTest {

  @Autowired private DispatchChannelCircuitBreaker circuitBreaker;
  @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;
  @Autowired private MeterRegistry meterRegistry;

  @Test
  void contextLoadsWithResilience4jAutoconfig() {
    assertThat(circuitBreakerRegistry)
        .as("R4J autoconfig must provide CircuitBreakerRegistry")
        .isNotNull();
    assertThat(circuitBreaker).isNotNull();
    assertThat(circuitBreaker.allow("t1|API|ch-1")).isTrue();
    assertThat(circuitBreaker.currentOpenCircuits()).isEqualTo(0);
  }

  /**
   * B3:验证本模块上下文里 R4J metrics 绑定链真活着(SB4/JDK25 下 CircuitBreakerMetricsAutoConfiguration
   * 未被静默剔除)。用<b>共享 autoconfig registry</b> 建一个探针熔断器,断言 {@code resilience4j.circuitbreaker.state}
   * 确被埋入——绑定链一旦断裂会「零 CB 指标而测试仍绿」,这条守住它。
   *
   * <p><b>覆盖边界(如实说明)</b>:这里断言的只是<b>共享 autoconfig registry</b> 的绑定链,与 {@link
   * DispatchChannelCircuitBreaker} 自持的 per-key registry 相互独立(见类注释)。自持 registry 的绑定见下方 {@link
   * #circuitBreakerMetricsBindsStateMeter_selfHeldRegistry()}。
   */
  @Test
  void circuitBreakerMetricsAutoconfigBindsStateMeter_sharedRegistryOnly() {
    circuitBreakerRegistry.circuitBreaker("dispatch-smoke-probe");

    assertThat(meterRegistry.find("resilience4j.circuitbreaker.state").meters())
        .as("metrics autoconfig must bind circuitbreaker.state gauge in worker-dispatch context")
        .isNotEmpty();
  }

  /**
   * #783 B3 覆盖缺口补齐:{@link DispatchChannelCircuitBreaker} 自持 per-key registry 不接共享 autoconfig
   * registry,之前只有 {@code batch.dispatch.circuits.open} 聚合 gauge、没有 R4J 明细指标覆盖。这里让某个 key 熔断
   * OPEN,断言注入的 {@link MeterRegistry} 里真出现该 breaker 的 {@code resilience4j.circuitbreaker.state}
   * meter(带 {@code name} tag 定位到具体 key),验证 {@code TaggedCircuitBreakerMetrics} 绑定链在 Spring 上下文里真活着。
   */
  @Test
  void circuitBreakerMetricsBindsStateMeter_selfHeldRegistry() {
    String key = "t-smoke|API|ch-smoke";
    // 默认 failureThreshold=5(见 DispatchCircuitBreakerProperties 默认值)
    for (int i = 0; i < 5; i++) {
      circuitBreaker.allow(key);
      circuitBreaker.recordFailure(key);
    }
    assertThat(circuitBreaker.allow(key)).as("breaker for key must be OPEN").isFalse();

    assertThat(meterRegistry.find("resilience4j.circuitbreaker.state").tag("name", key).meters())
        .as("self-held registry breaker state must be bound to the injected MeterRegistry")
        .isNotEmpty();
  }

  @SpringBootConfiguration
  @EnableConfigurationProperties(DispatchCircuitBreakerProperties.class)
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
    DispatchChannelCircuitBreaker dispatchChannelCircuitBreaker(
        DispatchCircuitBreakerProperties properties, MeterRegistry meterRegistry) {
      return new DispatchChannelCircuitBreaker(properties, meterRegistry);
    }
  }
}
