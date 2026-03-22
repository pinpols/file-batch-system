package com.example.batch.worker.dispatchs.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.worker.dispatch.circuit-breaker")
public class DispatchCircuitBreakerProperties {

    private boolean enabled = true;
    private int failureThreshold = 5;
    private long cooldownMillis = 60_000L;
}
