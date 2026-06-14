package com.example.batch.common.tenant.routing;

import com.example.batch.common.utils.Texts;
import java.util.Map;

/**
 * Tiered 放置:多数租户哈希到 {@code pooledShardCount} 个 pooled 分片;指定(巨型 / 合规)租户经 {@code siloOverrides} 路由到独占
 * silo。
 *
 * <ul>
 *   <li><b>pooledShardCount=1 且无 silo</b> = 当前行为(全部落 {@code shard-0}),落地第一步无损。
 *   <li><b>哈希用 {@link String#hashCode()}</b>:JLS 规定跨 JVM 稳定 → 路由确定,同租户恒落同片。
 *   <li><b>无租户上下文</b>(null/空)→ {@code shard-0} 兜底(不抛,避免无租户的基础设施查询挂掉)。
 * </ul>
 *
 * <p>不可变、线程安全。placement 数据(分片数 / silo 列表)来自租户维护(见 biz_tenant_placement), 由装配方注入;凭据不在此(走
 * secrets,见方案文档)。
 */
public final class HashAndSiloPlacementResolver implements BusinessPlacementResolver {

  /** 单片兜底 key,也是无租户上下文时的归宿。 */
  public static final String DEFAULT_KEY = "shard-0";

  private final int pooledShardCount;
  private final Map<String, String> siloOverrides;

  /**
   * @param pooledShardCount pooled 分片数(≥1;=1 即单片无损)
   * @param siloOverrides tenant_id → silo 数据源 key(独占租户);可空
   */
  public HashAndSiloPlacementResolver(int pooledShardCount, Map<String, String> siloOverrides) {
    if (pooledShardCount < 1) {
      throw new IllegalArgumentException("pooledShardCount must be >= 1, got " + pooledShardCount);
    }
    this.pooledShardCount = pooledShardCount;
    this.siloOverrides = siloOverrides == null ? Map.of() : Map.copyOf(siloOverrides);
  }

  @Override
  public String resolve(String tenantId) {
    if (!Texts.hasText(tenantId)) {
      return DEFAULT_KEY;
    }
    String silo = siloOverrides.get(tenantId);
    if (silo != null) {
      return silo;
    }
    return "shard-" + Math.floorMod(tenantId.hashCode(), pooledShardCount);
  }
}
