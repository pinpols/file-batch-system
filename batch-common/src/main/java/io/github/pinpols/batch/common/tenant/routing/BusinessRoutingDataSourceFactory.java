package io.github.pinpols.batch.common.tenant.routing;

import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;

/**
 * 装配 {@link BusinessRoutingDataSource} 的工厂,供各 worker 的 BusinessDataSourceConfiguration 复用, 避免 3
 * 处各写一遍 targetDataSources/default 装配。
 */
public final class BusinessRoutingDataSourceFactory {

  private BusinessRoutingDataSourceFactory() {}

  /**
   * 单片(无损)装配:全部租户路由到唯一 shard-0 = 传入的现有 biz 数据源,行为等价接线前。
   *
   * <p>P1-2 落地第一步用此:把现有 Hikari 包成路由 DS,建立路由 seam 但零行为变更;P2 扩多片时 改成多 target + pooledShardCount>1
   * 即可,worker 装配不再动。
   *
   * @param shard0 现有 biz 数据源(成为唯一 placement)
   * @return 路由 DataSource(已 afterPropertiesSet,可直接当 bean 用)
   */
  public static DataSource singleShard(DataSource shard0) {
    BusinessRoutingDataSource routing =
        new BusinessRoutingDataSource(new HashAndSiloPlacementResolver(1, Map.of()));
    routing.setTargetDataSources(
        Map.<Object, Object>of(HashAndSiloPlacementResolver.DEFAULT_KEY, shard0));
    routing.setDefaultTargetDataSource(shard0);
    routing.afterPropertiesSet();
    return routing;
  }

  /**
   * 多片装配(P2 使能):按 {@code shards}(placement key → DataSource)+ resolver 路由。
   *
   * <p>{@code shards} 的 key 必须覆盖 resolver 可能返回的全部 key(shard-0..N-1 + 各 silo);
   * defaultTargetDataSource 用 shard-0 回退(无租户上下文时)。N 个真实 DataSource 的构造 (从 config + secrets)由装配方/ops
   * 提供——本工厂只负责组装路由,不碰凭据。
   *
   * @param shards placement key → 已建好的 DataSource;必须含 {@link
   *     HashAndSiloPlacementResolver#DEFAULT_KEY}
   * @param resolver 租户→placement key 解析器(pooledShardCount 与 silo 须与 shards 的 key 一致)
   */
  public static DataSource multiShard(
      Map<String, DataSource> shards, BusinessPlacementResolver resolver) {
    DataSource fallback = shards.get(HashAndSiloPlacementResolver.DEFAULT_KEY);
    if (fallback == null) {
      throw new IllegalArgumentException(
          "shards must contain default key " + HashAndSiloPlacementResolver.DEFAULT_KEY);
    }
    BusinessRoutingDataSource routing = new BusinessRoutingDataSource(resolver);
    routing.setTargetDataSources(new HashMap<Object, Object>(shards));
    routing.setDefaultTargetDataSource(fallback);
    // 关 lenientFallback:resolver 返回未配置的 key(typo silo / 迁片到不存在的片)时**硬失败**,
    // 而非静默降级到 default(shard-0)。静默降级会把该租户数据写到错误分片且无报错(数据污染),
    // 宁可该租户 task 当场抛错暴露配置问题。无租户上下文 → resolver 返 shard-0(已配置)不受影响。
    routing.setLenientFallback(false);
    routing.afterPropertiesSet();
    return routing;
  }
}
