package io.github.pinpols.batch.worker.dispatchs.config;

import io.github.pinpols.batch.worker.core.config.BaseWorkerProperties;
import io.github.pinpols.batch.worker.core.config.WorkerConfiguration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 分发 Worker 配置，绑定 {@code batch.worker.dispatch} 前缀属性。字段与规范化逻辑下沉到 {@link
 * BaseWorkerProperties}，本类只保留模块独有的 {@code @ConfigurationProperties} 前缀绑定。
 */
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

  public DispatchWorkerConfiguration {
    capabilityTags = BaseWorkerProperties.normalizeCapabilityTags(capabilityTags);
  }
}
