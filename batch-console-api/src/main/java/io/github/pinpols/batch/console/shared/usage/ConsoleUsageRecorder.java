package io.github.pinpols.batch.console.shared.usage;

import java.time.Instant;

/** 操作使用率记录端口；审计上下文只依赖这个最小共享契约。 */
public interface ConsoleUsageRecorder {

  /** 记录一次操作结果；具体聚合和持久化由 observability 上下文负责。 */
  void record(String tenantId, String action, boolean success, Instant createdAt);
}
