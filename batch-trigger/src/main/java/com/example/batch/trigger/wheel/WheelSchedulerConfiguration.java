package com.example.batch.trigger.wheel;

import com.example.batch.trigger.config.WheelSchedulerProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间轮 scheduler 的 bean 装配 — 仅在 {@code batch.trigger.scheduler-impl=wheel} 时生效。
 *
 * <p>Quartz 模式(默认)下整个 wheel 子系统不创建,无任何资源占用。
 */
@Configuration
@ConditionalOnProperty(name = "batch.trigger.scheduler-impl", havingValue = "wheel")
@EnableConfigurationProperties(WheelSchedulerProperties.class)
public class WheelSchedulerConfiguration {

  @Bean
  public WheelMetrics wheelMetrics(MeterRegistry meterRegistry) {
    return new WheelMetrics(meterRegistry);
  }

  @Bean
  public CatchUpThrottle catchUpThrottle(WheelSchedulerProperties props) {
    return new CatchUpThrottle(props.getCatchUpRatePerSecond());
  }
}
