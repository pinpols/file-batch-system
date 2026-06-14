package com.example.batch.common.tenant.routing;

/**
 * 把租户解析到它的 biz 数据源放置点(placement key)。
 *
 * <p>tenant-routing 的核心:决定某租户的 biz 数据落哪个库/实例。返回的 key 对应 {@link BusinessRoutingDataSource}
 * targetDataSources 里的一个数据源。
 *
 * <p>放置策略(由实现决定):
 *
 * <ul>
 *   <li><b>Pooled 分片</b>:多租户哈希到 N 个 shard,每个 shard 仍是多租 pool(片内靠 RLS 隔离)。
 *   <li><b>Silo</b>:特定(巨型 / 合规)租户路由到独占库,库内单租户、无需 RLS。
 * </ul>
 *
 * <p>单片(N=1、无 silo)= 当前行为,全部落同一库 —— 落地第一步保持无损。
 */
public interface BusinessPlacementResolver {

  /**
   * @param tenantId 当前租户(取自 {@code RlsTenantContextHolder.get()});null/空 表示无租户上下文
   * @return placement key(数据源标识);永不返回 null
   */
  String resolve(String tenantId);
}
