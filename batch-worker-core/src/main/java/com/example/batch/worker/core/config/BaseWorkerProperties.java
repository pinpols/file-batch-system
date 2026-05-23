package com.example.batch.worker.core.config;

import java.util.List;

/**
 * 所有 worker 模块（process / dispatch / import / export）共用的标准属性 record。
 *
 * <p>各 worker 模块的 {@code @ConfigurationProperties} record 需保持自有类型以绑定独立前缀
 * （{@code batch.worker.process} / {@code batch.worker.dispatch} 等），但字段与 capabilityTags
 * 的 null → 空列表规范化逻辑完全相同。把规范化静态化为 {@link #normalizeCapabilityTags(List)} 后，
 * 各模块的 compact constructor 一行调用即可，避免每个 record 写一遍同样的 override。
 *
 * <p>新增 worker 类型时可直接用本 record 作为 {@code @ConfigurationProperties} 目标（命名上若不需要
 * 与模块强绑定），免去再 copy 一份字段列表。
 */
public record BaseWorkerProperties(
    String workerCode,
    String workerType,
    String tenantId,
    Long heartbeatIntervalMillis,
    String topic,
    String consumerGroupId,
    List<String> capabilityTags)
    implements WorkerConfiguration {

  public BaseWorkerProperties {
    capabilityTags = normalizeCapabilityTags(capabilityTags);
  }

  /** Spring 绑定缺省时 capabilityTags 为 null，这里统一替换为不可变空列表，业务侧 forEach 安全。 */
  public static List<String> normalizeCapabilityTags(List<String> capabilityTags) {
    return capabilityTags == null ? List.of() : capabilityTags;
  }
}
