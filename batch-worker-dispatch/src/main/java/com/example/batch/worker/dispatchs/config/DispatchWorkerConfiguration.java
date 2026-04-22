package com.example.batch.worker.dispatchs.config;

import com.example.batch.worker.core.config.WorkerConfiguration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 分发 Worker 配置，绑定 {@code batch.worker.dispatch} 前缀属性。 */
@ConfigurationProperties(prefix = "batch.worker.dispatch")
public record DispatchWorkerConfiguration(
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
