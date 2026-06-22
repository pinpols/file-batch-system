package com.example.batch.worker.core.config;

import java.util.List;

/**
 * 所有 worker 类型（import / export / dispatch）的通用配置契约。 各 worker 模块的 {@code @ConfigurationProperties}
 * record 实现此接口， 使 {@code batch-worker-core} 中的共享生命周期与消费模板无需依赖特定模块的配置类。
 */
public interface WorkerConfiguration {

  /** 固定 worker 标识符；设置后直接用作 workerId。 */
  String workerCode();

  /** Worker 类型判别符（如 "IMPORT"、"EXPORT"、"DISPATCH"）。 */
  String workerType();

  /** 该 worker 实例所属租户。 */
  String tenantId();

  /** 心跳间隔毫秒数（可为 null，调用方须应用默认值）。 */
  Long heartbeatIntervalMillis();

  /** worker 消费的 Kafka topic。 */
  String topic();

  /** Kafka 消费者组 ID。 */
  String consumerGroupId();

  /**
   * 能力标签列表，心跳上报到 orchestrator 后写入 {@code worker_registry.capability_tags} JSONB。 配合带 {@code
   * resource_tag} 的 queue 做细粒度路由（参见 {@code DefaultWorkerSelector.matchesResourceTag}）。
   * 默认返回空列表；worker 实现若不覆写等同于「不声明任何能力」。
   */
  default List<String> capabilityTags() {
    return List.of();
  }
}
