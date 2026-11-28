package io.github.pinpols.batch.worker.dispatchs.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/** 分发渠道熔断器配置属性。 */
@Data
@ConfigurationProperties(prefix = "batch.worker.dispatch.circuit-breaker")
public class DispatchCircuitBreakerProperties {

    private boolean enabled = true;
    private int failureThreshold = 5;
    private long cooldownMillis = 60_000L;
}
