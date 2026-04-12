package com.example.batch.worker.imports.config;

import com.example.batch.worker.core.config.WorkerConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "batch.worker.import")
public record ImportWorkerConfiguration(
    String workerCode,
    String workerType,
    String tenantId,
    Long heartbeatIntervalMillis,
    String topic,
    String consumerGroupId,
    FileProcessing fileProcessing)
    implements WorkerConfiguration {
  private static final int DEFAULT_PAGE_SIZE = 1000;
  private static final int DEFAULT_FETCH_SIZE = 1000;
  private static final int DEFAULT_CHUNK_SIZE = 500;

  public boolean streamingEnabled() {
    return fileProcessing != null && fileProcessing.streamingEnabled();
  }

  public int pageSize() {
    return fileProcessing == null || fileProcessing.pageSize() <= 0
        ? DEFAULT_PAGE_SIZE
        : fileProcessing.pageSize();
  }

  public int fetchSize() {
    return fileProcessing == null || fileProcessing.fetchSize() <= 0
        ? DEFAULT_FETCH_SIZE
        : fileProcessing.fetchSize();
  }

  public int chunkSize() {
    return fileProcessing == null || fileProcessing.chunkSize() <= 0
        ? DEFAULT_CHUNK_SIZE
        : fileProcessing.chunkSize();
  }

  public record FileProcessing(
      boolean streamingEnabled, int pageSize, int fetchSize, int chunkSize) {}
}
