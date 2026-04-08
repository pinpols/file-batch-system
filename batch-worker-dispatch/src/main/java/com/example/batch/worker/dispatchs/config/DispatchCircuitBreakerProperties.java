package com.example.batch.worker.dispatchs.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 分发渠道熔断器配置属性。
 */
@Data
@ConfigurationProperties(prefix = "batch.worker.dispatch.circuit-breaker")
public class DispatchCircuitBreakerProperties {

    private boolean enabled = true;
    private int failureThreshold = 5;
    private long cooldownMillis = 60_000L;
}
