package com.example.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.resource-scheduler")
public class ResourceSchedulerProperties {

    private int waitingDispatchBatchSize = 100;
    private long waitingDispatchIntervalMillis = 10000L;
    private int quotaResetSlidingWindowHours = 24;
    private long quotaResetScanIntervalMillis = 60000L;
}
