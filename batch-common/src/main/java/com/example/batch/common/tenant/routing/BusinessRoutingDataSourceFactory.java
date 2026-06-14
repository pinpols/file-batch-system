package com.example.batch.common.tenant.routing;

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
}
