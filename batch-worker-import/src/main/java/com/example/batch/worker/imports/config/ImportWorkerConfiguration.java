package com.example.batch.worker.imports.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "batch.worker.import")
public record ImportWorkerConfiguration(
        String workerCode,
        String workerType,
        String tenantId,
        Long heartbeatIntervalMillis,
        String topic,
        String consumerGroupId,
        FileProcessing fileProcessing
) {
    public boolean streamingEnabled() {
        return fileProcessing != null && fileProcessing.streamingEnabled();
    }

    public int pageSize() {
        return fileProcessing == null || fileProcessing.pageSize() <= 0 ? 1000 : fileProcessing.pageSize();
    }

    public int fetchSize() {
        return fileProcessing == null || fileProcessing.fetchSize() <= 0 ? 1000 : fileProcessing.fetchSize();
    }

    public int chunkSize() {
        return fileProcessing == null || fileProcessing.chunkSize() <= 0 ? 500 : fileProcessing.chunkSize();
    }

    public record FileProcessing(
            boolean streamingEnabled,
            int pageSize,
            int fetchSize,
            int chunkSize
    ) {
    }
}
