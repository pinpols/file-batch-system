package com.example.batch.worker.dispatchs.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.worker.dispatchs.infrastructure.channel.DispatchChannelCircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dispatch 投递指标采集单测：
 *
 * <ul>
 *   <li>{@code batch.dispatch.deliveries} 按 channel_type / result 维度分桶
 *   <li>circuitRejected=true 时 result tag 必须是 {@code circuit_open}（不要看 success）
 *   <li>channel_type=null 时 fallback 到 {@code unknown}
 *   <li>{@code batch.dispatch.circuits.open} gauge 反映 circuit breaker 实时开路数
 * </ul>
 */
class DispatchDeliveryMetricsTest {

  private SimpleMeterRegistry registry;
  private DispatchChannelCircuitBreaker circuitBreaker;
  private DispatchDeliveryMetrics metrics;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    circuitBreaker = mock(DispatchChannelCircuitBreaker.class);
    metrics = new DispatchDeliveryMetrics(registry, circuitBreaker);
    metrics.registerOpenCircuitGauge();
  }

  @Test
  @DisplayName("success → result=success counter +1")
  void shouldRecordSuccessCounter_whenSuccessTrue() {
    metrics.recordDelivery("SFTP", true, false);

    assertThat(
            registry
                .find("batch.dispatch.deliveries")
                .tag("channel_type", "SFTP")
                .tag("result", "success")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("failure（非熔断）→ result=failure counter +1")
  void shouldRecordFailureCounter_whenSuccessFalseAndNotRejected() {
    metrics.recordDelivery("OSS", false, false);

    assertThat(
            registry
                .find("batch.dispatch.deliveries")
                .tag("channel_type", "OSS")
                .tag("result", "failure")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName(
      "circuitRejected=TRUE 优先于 success → result=circuit_open（即使 success=true 也算 circuit_open）")
  void shouldRecordCircuitOpen_whenCircuitRejected() {
    metrics.recordDelivery("API", true, true);
    metrics.recordDelivery("API", false, true);

    assertThat(
            registry
                .find("batch.dispatch.deliveries")
                .tag("channel_type", "API")
                .tag("result", "circuit_open")
                .counter()
                .count())
        .isEqualTo(2.0);
    // success/failure 桶不应被触达
    assertThat(
            registry
                .find("batch.dispatch.deliveries")
                .tag("channel_type", "API")
                .tag("result", "success")
                .counter())
        .isNull();
  }

  @Test
  @DisplayName("channel_type=null → tag 值为 unknown")
  void shouldUseUnknownTag_whenChannelTypeNull() {
    metrics.recordDelivery(null, true, false);

    assertThat(
            registry
                .find("batch.dispatch.deliveries")
                .tag("channel_type", "unknown")
                .tag("result", "success")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("多次同 tag → 同一 counter 累加，不重复注册 meter")
  void shouldAccumulateCounter_whenSameTagsRepeated() {
    metrics.recordDelivery("NAS", true, false);
    metrics.recordDelivery("NAS", true, false);
    metrics.recordDelivery("NAS", true, false);

    assertThat(
            registry
                .find("batch.dispatch.deliveries")
                .tag("channel_type", "NAS")
                .tag("result", "success")
                .counter()
                .count())
        .isEqualTo(3.0);
  }

  @Test
  @DisplayName("batch.dispatch.circuits.open gauge → 反映 circuit breaker.currentOpenCircuits()")
  void shouldExposeOpenCircuitsGauge_fromCircuitBreaker() {
    when(circuitBreaker.currentOpenCircuits()).thenReturn(3);

    Double value = registry.find("batch.dispatch.circuits.open").gauge().value();

    assertThat(value).isEqualTo(3.0);
  }
}
