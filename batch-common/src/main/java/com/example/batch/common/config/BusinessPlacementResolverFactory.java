package com.example.batch.common.config;

import com.example.batch.common.tenant.routing.BusinessPlacementResolver;
import com.example.batch.common.tenant.routing.DbTablePlacementResolver;
import com.example.batch.common.tenant.routing.HashAndSiloPlacementResolver;
import com.example.batch.common.tenant.routing.TenantPlacementRepository;

/**
 * 按 {@link BusinessRoutingProperties#getPlacementSource()} 装配 placement resolver:
 *
 * <ul>
 *   <li>CONFIG → {@link HashAndSiloPlacementResolver}(hash 池化 + siloOverrides)。
 *   <li>TABLE → {@link DbTablePlacementResolver}(platform 表覆盖)+ hash 回退(未登记租户)。
 * </ul>
 *
 * <p>TABLE 模式需 {@link TenantPlacementRepository}(经 MyBatis 读 {@code
 * batch.business_tenant_placement}); hash 回退参数(pooledShardCount / siloOverrides)两模式共用,故 TABLE
 * 也支持「少量登记 + 多数 hash」。
 */
public final class BusinessPlacementResolverFactory {

  private BusinessPlacementResolverFactory() {}

  public static BusinessPlacementResolver create(
      BusinessRoutingProperties routingProperties, TenantPlacementRepository placementRepository) {
    HashAndSiloPlacementResolver hashFallback =
        new HashAndSiloPlacementResolver(
            routingProperties.getPooledShardCount(), routingProperties.getSiloOverrides());
    if (routingProperties.getPlacementSource() == BusinessRoutingProperties.PlacementSource.TABLE) {
      return new DbTablePlacementResolver(
          placementRepository,
          hashFallback,
          routingProperties.getPlacementCacheTtlMs(),
          System::currentTimeMillis);
    }
    return hashFallback;
  }
}
