package io.github.pinpols.batch.orchestrator.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({TimedAspect.class, MeterRegistry.class})
/** 装配 Orchestrator 服务方法的指标切面。 */
public class OrchestratorMetricsAspectConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public TimedAspect timedAspect(MeterRegistry meterRegistry) {
    return new TimedAspect(meterRegistry);
  }
}
