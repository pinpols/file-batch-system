package io.github.pinpols.batch.common.config;

import java.time.Instant;

/** Console 配置变更提交后广播给 Orchestrator 的本地缓存失效事件。 */
public record ConfigCacheInvalidationEvent(
    String tenantId, String type, String code, long revision, long keyRevision, Instant changedAt) {

  public boolean wildcard() {
    return "*".equals(code);
  }
}
