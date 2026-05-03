package com.example.batch.common.config;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 提供 {@code @Observed} 注解的 AOP 拦截支持，把业务方法包成 Micrometer Observation。Observation 自动桥接到 {@code
 * micrometer-tracing-bridge-otel} 上的 OTel SDK，再由 {@code opentelemetry-exporter-otlp} 推到 OTel
 * Collector → Tempo / Jaeger。
 *
 * <p>未提供 {@link ObservedAspect} bean 时，业务代码上的 {@code @Observed} 注解会被静默忽略；Spring Boot 4.x 的 {@code
 * ObservationAutoConfiguration} 仅创建 {@link ObservationRegistry}，不创建 aspect（设计上留给应用决定）。
 *
 * <p>详见 {@code docs/architecture/adr/ADR-013-distributed-tracing.md} + {@code
 * docs/runbook/distributed-tracing.md}。
 */
@AutoConfiguration
@ConditionalOnClass({ObservationRegistry.class, ObservedAspect.class})
@ConditionalOnBean(ObservationRegistry.class)
public class BatchObservabilityAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
    return new ObservedAspect(observationRegistry);
  }
}
