package io.github.pinpols.batch.worker.dispatchs.infrastructure;

import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchChannelCircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** 分发投递指标采集，包括熔断器开路数量和各渠道投递结果计数。 */
@Component
public class DispatchDeliveryMetrics {

  private final MeterRegistry registry;
  private final DispatchChannelCircuitBreaker circuitBreaker;

  public DispatchDeliveryMetrics(
      MeterRegistry registry, DispatchChannelCircuitBreaker circuitBreaker) {
    this.registry = registry;
    this.circuitBreaker = circuitBreaker;
  }

  @PostConstruct
  void registerOpenCircuitGauge() {
    Gauge.builder(
            "batch.dispatch.circuits.open",
            circuitBreaker,
            DispatchChannelCircuitBreaker::currentOpenCircuits)
        .description("当前处于熔断冷却期的分发渠道数量")
        .register(registry);
  }

  public void recordDelivery(String channelType, boolean success, boolean circuitRejected) {
    String result = circuitRejected ? "circuit_open" : (success ? "success" : "failure");
    Counter.builder("batch.dispatch.deliveries")
        .tag("channel_type", channelType == null ? "unknown" : channelType)
        .tag("result", result)
        .register(registry)
        .increment();
  }
}
