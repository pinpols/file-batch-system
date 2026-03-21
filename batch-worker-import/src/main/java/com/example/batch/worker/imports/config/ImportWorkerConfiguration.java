package com.example.batch.worker.imports.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "batch.worker.import")
public record ImportWorkerConfiguration(
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
