package com.example.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.outbox")
public class OutboxProperties {

    private int batchSize = 100;
    private long pollIntervalMillis = 5000L;
    private long retryDelaySeconds = 60L;
    private int maxRetryAttempts = 5;
    private String producerName = "batch-orchestrator";
    private String defaultTopic = "batch.outbox.event";

    /**
     * Outbox 投递熔断：当连续若干轮推进中出现失败，进入 cooldown，避免失败重试放大问题。
     */
    private boolean circuitBreakerEnabled = true;
    private int circuitBreakerFailureThresholdConsecutivePolls = 3;
    private long circuitBreakerCooldownMillis = 60000L;
}
