package com.example.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.outbox")
public class OutboxProperties {

    private int batchSize = 100;
    private long pollIntervalMillis = 5000L;
    private long retryDelaySeconds = 60L;
    private String producerName = "batch-orchestrator";
    private String defaultTopic = "batch.outbox.event";
}
