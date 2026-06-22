package com.example.batch.worker.processes.config;

import com.example.batch.worker.core.config.BaseWorkerProperties;
import com.example.batch.worker.core.config.WorkerConfiguration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 加工 Worker 配置属性,绑定前缀 {@code batch.worker.process}。字段与规范化逻辑下沉到 {@link
 * BaseWorkerProperties}，本类只保留模块独有的 {@code @ConfigurationProperties} 前缀绑定。
 */
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

  public ProcessWorkerConfiguration {
    capabilityTags = BaseWorkerProperties.normalizeCapabilityTags(capabilityTags);
  }
}
