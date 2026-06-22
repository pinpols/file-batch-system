package com.example.batch.worker.atomic.config;

import com.example.batch.worker.core.config.BaseWorkerProperties;
import com.example.batch.worker.core.config.WorkerConfiguration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 专用原子任务 worker 身份配置,绑定 {@code batch.worker.spi} 前缀。
 *
 * <p>与 {@code BatchWorkerAtomicProperties}(同前缀,绑 {@code enabled-types})共存 —— 两个
 * {@code @ConfigurationProperties} 共享前缀、子键不冲突即可,Spring 各绑各的。workerType 固定 TASK, 消费专属 topic
 * batch.task.dispatch.atomic(ADR-029)。
 */
@ConfigurationProperties(prefix = "batch.worker.atomic")
public record AtomicWorkerConfiguration(
    String workerCode,
    String workerType,
    String tenantId,
    Long heartbeatIntervalMillis,
    String topic,
    String consumerGroupId,
    List<String> capabilityTags)
    implements WorkerConfiguration {

  public AtomicWorkerConfiguration {
    capabilityTags = BaseWorkerProperties.normalizeCapabilityTags(capabilityTags);
  }
}
