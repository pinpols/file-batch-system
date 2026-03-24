package com.example.batch.worker.dispatchs.config;

import com.example.batch.worker.core.config.WorkerConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "batch.worker.dispatch")
public record DispatchWorkerConfiguration(
        String workerCode,
        String workerType,
        String tenantId,
        Long heartbeatIntervalMillis,
        String topic,
        String consumerGroupId
) implements WorkerConfiguration {
}
