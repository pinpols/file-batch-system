package io.github.pinpols.batch.common.redis;

public final class BatchRedisKeys {

  private BatchRedisKeys() {}

  public static String rateLimit(String tenantId, String action, long windowStartEpochSecond) {
    return "ratelimit:%s:%s:%d".formatted(safe(tenantId), safe(action), windowStartEpochSecond);
  }

  public static String outboxCircuit() {
    return "circuit:outbox_publish";
  }

  public static String shedLock(String environment, String name) {
    return "shedlock:%s:%s".formatted(safe(environment), safe(name));
  }

  public static String config(String tenantId, String type, String code) {
    return "config:%s:%s:%s".formatted(safe(tenantId), safe(type), safe(code));
  }

  public static String fileGovernanceMetrics(String tenantId) {
    return "metrics:file_governance:%s".formatted(safe(tenantId));
  }

  /**
   * 配额运行时状态 Hash key：每 (tenantId, scope, ownerCode) 一份；scope 取值 TENANT_JOBS / TENANT_PARTITIONS /
   * QUEUE_JOBS / QUEUE_PARTITIONS。
   */
  public static String quotaState(String tenantId, String scope, String ownerCode) {
    return "quota:state:%s:%s:%s".formatted(safe(tenantId), safe(scope), safe(ownerCode));
  }

  /** 配额状态索引（按租户）：用于 snapshot 调度器扫描某租户下所有 owner，避免全库 SCAN。 */
  public static String quotaStateIndex(String tenantId) {
    return "quota:index:%s".formatted(safe(tenantId));
  }

  private static String safe(String value) {
    if (value == null || value.isBlank()) {
      return "_";
    }
    return value.replace(':', '_');
  }
}
