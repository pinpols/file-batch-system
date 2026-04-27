package com.example.batch.worker.processes.config;

import com.example.batch.worker.core.config.WorkerConfiguration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 加工 Worker 配置属性,绑定前缀 {@code batch.worker.process}。 */
@ConfigurationProperties(prefix = "batch.worker.process")
public record ProcessWorkerConfiguration(
    String workerCode,
    String workerType,
    String tenantId,
    Long heartbeatIntervalMillis,
    String topic,
    String consumerGroupId,
    List<String> capabilityTags)
    implements WorkerConfiguration {

  @Override
  public List<String> capabilityTags() {
    return capabilityTags == null ? List.of() : capabilityTags;
  }
}
