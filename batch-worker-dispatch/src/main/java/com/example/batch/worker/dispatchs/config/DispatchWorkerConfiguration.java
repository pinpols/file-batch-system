package com.example.batch.worker.dispatchs.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "batch.worker.dispatch")
public record DispatchWorkerConfiguration(
        String workerCode,
        String workerType,
        String defaultStage,
        Integer batchSize,
        String tenantId,
        Long heartbeatIntervalMillis,
        String topic,
        String consumerGroupId
) {
}
