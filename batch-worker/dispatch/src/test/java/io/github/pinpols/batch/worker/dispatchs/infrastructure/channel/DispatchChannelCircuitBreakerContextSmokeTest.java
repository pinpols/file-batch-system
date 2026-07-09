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
   * <p><b>覆盖边界(如实说明)</b>:这里断言的只是<b>共享 autoconfig registry</b> 的绑定链。而 {@link
   * DispatchChannelCircuitBreaker} 有意用<b>自持 per-key registry</b>(见类注释,与 autoconfig 解耦以隔离 {@code
   * currentOpenCircuits()} 语义),因此<b>该 breaker 自持 registry 是否真吐指标本用例并不覆盖</b>—— 给它的 per-key registry
   * 加 micrometer 绑定并断言属后续工作(follow-up)。
   */
  @Test
  void circuitBreakerMetricsAutoconfigBindsStateMeter_sharedRegistryOnly() {
    circuitBreakerRegistry.circuitBreaker("dispatch-smoke-probe");

    assertThat(meterRegistry.find("resilience4j.circuitbreaker.state").meters())
        .as("metrics autoconfig must bind circuitbreaker.state gauge in worker-dispatch context")
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
        DispatchCircuitBreakerProperties properties) {
      return new DispatchChannelCircuitBreaker(properties);
    }
  }
}
