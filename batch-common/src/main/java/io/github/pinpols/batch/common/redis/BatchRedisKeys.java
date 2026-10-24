package io.github.pinpols.batch.common.redis;

public final class BatchRedisKeys {

  public static final String OUTBOX_CIRCUIT = "circuit:outbox_publish";

  private BatchRedisKeys() {}

  /**
   * 租户动作限流令牌桶 key：每 (tenantId, action) 一个 Bucket4j 桶。不含时间窗——令牌桶状态由 bucket4j 在 Redis 侧按 refill
   * 速率累积，无需客户端窗口对齐。
   */
  public static String rateLimitBucket(String tenantId, String action) {
    String safeTenantId = safe(tenantId);
    String safeAction = safe(action);
    return "ratelimit:%s:%s".formatted(safeTenantId, safeAction);
  }

  public static String outboxCircuit() {
    return OUTBOX_CIRCUIT;
  }

  public static String shedLock(String environment, String name) {
    String safeEnvironment = safe(environment);
    String safeName = safe(name);
    return "shedlock:%s:%s".formatted(safeEnvironment, safeName);
  }

  public static String config(String tenantId, String type, String code) {
    String safeTenantId = safe(tenantId);
    String safeType = safe(type);
    String safeCode = safe(code);
    return "config:%s:%s:%s".formatted(safeTenantId, safeType, safeCode);
  }

  public static String configInvalidationGlobalRevision() {
    return "config:invalidation:revision:global";
  }

  public static String configInvalidationKeyRevision(String tenantId, String type, String code) {
    String safeTenantId = safe(tenantId);
    String safeType = safe(type);
    String safeCode = safe(code);
    return "config:invalidation:revision:%s:%s:%s".formatted(safeTenantId, safeType, safeCode);
  }

  public static String fileGovernanceMetrics(String tenantId) {
    return "metrics:file_governance:%s".formatted(safe(tenantId));
  }

  /**
   * 配额运行时状态 Hash key：每 (tenantId, scope, ownerCode) 一份；scope 取值 TENANT_JOBS / TENANT_PARTITIONS /
   * QUEUE_JOBS / QUEUE_PARTITIONS。
   */
  public static String quotaState(String tenantId, String scope, String ownerCode) {
    String safeTenantId = safe(tenantId);
    String safeScope = safe(scope);
    String safeOwnerCode = safe(ownerCode);
    return "quota:state:%s:%s:%s".formatted(safeTenantId, safeScope, safeOwnerCode);
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
