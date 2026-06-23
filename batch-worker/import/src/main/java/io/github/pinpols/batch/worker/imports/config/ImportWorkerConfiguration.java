package io.github.pinpols.batch.worker.imports.config;

import io.github.pinpols.batch.worker.core.config.WorkerConfiguration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "batch.worker.import")
public record ImportWorkerConfiguration(
    String workerCode,
    String workerType,
    String tenantId,
    Long heartbeatIntervalMillis,
    String topic,
    String consumerGroupId,
    List<String> capabilityTags,
    FileProcessing fileProcessing,
    /** 心跳仍用 {@link #tenantId}；仅放宽 Kafka 派发消息的租户白名单（E2E 单 JVM 验多租户）。 */
    Boolean acceptCrossTenantDispatch)
    implements WorkerConfiguration {

  @Override
  public List<String> capabilityTags() {
    return capabilityTags == null ? List.of() : capabilityTags;
  }

  private static final int DEFAULT_PAGE_SIZE = 1000;
  private static final int DEFAULT_FETCH_SIZE = 1000;
  private static final int DEFAULT_CHUNK_SIZE = 2000;
  private static final int DEFAULT_MAX_CHUNK_SIZE = 10000;

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

  public int maxChunkSize() {
    return fileProcessing == null || fileProcessing.maxChunkSize() <= 0
        ? DEFAULT_MAX_CHUNK_SIZE
        : fileProcessing.maxChunkSize();
  }

  public record FileProcessing(
      boolean streamingEnabled, int pageSize, int fetchSize, int chunkSize, int maxChunkSize) {

    public FileProcessing(boolean streamingEnabled, int pageSize, int fetchSize, int chunkSize) {
      this(streamingEnabled, pageSize, fetchSize, chunkSize, DEFAULT_MAX_CHUNK_SIZE);
    }
  }
}
