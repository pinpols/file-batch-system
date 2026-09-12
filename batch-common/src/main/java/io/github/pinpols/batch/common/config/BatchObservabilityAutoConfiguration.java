package io.github.pinpols.batch.common.config;

import io.github.pinpols.batch.common.logging.OpenTelemetryLogbackBridge;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 提供 {@code @Observed} 注解的 AOP 拦截支持，把业务方法包成 Micrometer Observation。Observation 自动桥接到 {@code
 * spring-boot-starter-opentelemetry} 装配的 OTel SDK，再通过 OTLP 推到 Collector。
 *
 * <p>未提供 {@link ObservedAspect} bean 时，业务代码上的 {@code @Observed} 注解会被静默忽略；Spring Boot 4.x 的 {@code
 * ObservationAutoConfiguration} 仅创建 {@link ObservationRegistry}，不创建 aspect（设计上留给应用决定）。
 *
 * <p>详见 {@code docs/architecture/adr/ADR-013-distributed-tracing.md} + {@code
 * docs/runbook/distributed-tracing.md}。
 */
@AutoConfiguration(
    afterName =
        "org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration")
@ConditionalOnClass({ObservationRegistry.class, ObservedAspect.class})
@ConditionalOnBean(ObservationRegistry.class)
public class BatchObservabilityAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
    return new ObservedAspect(observationRegistry);
  }

  @Bean
  @ConditionalOnBean(OpenTelemetry.class)
  @ConditionalOnMissingBean
  public OpenTelemetryLogbackBridge openTelemetryLogbackBridge(OpenTelemetry openTelemetry) {
    return new OpenTelemetryLogbackBridge(openTelemetry);
  }
}
