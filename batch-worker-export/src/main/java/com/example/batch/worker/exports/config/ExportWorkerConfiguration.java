package com.example.batch.worker.exports.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "batch.worker.export")
public record ExportWorkerConfiguration(
        String workerCode,
        String workerType,
        String tenantId,
        Long heartbeatIntervalMillis,
        String topic,
        String consumerGroupId
) {
}
