package com.example.batch.common.tenant.routing;

import java.util.Map;

/**
 * 读取「租户 → placement key」显式映射(P2 tenant-routing 表驱动 placement 的数据源)。
 *
 * <p>由 {@link DbTablePlacementResolver} 周期性整表加载到内存缓存,故只需 {@link #loadAll()} 全量读。
 * 读失败应<b>抛出</b>(不要吞成空 map):resolver 据此区分"空表"与"读失败"——前者退 hash,后者保留 上次缓存(stale),避免 silo 租户在表 outage
 * 期被 hash 误路由到错误分片。
 */
public interface TenantPlacementRepository {

  /**
   * 全量加载 tenant_id → placement_key。读失败抛出(由 resolver 处理 stale/cold 降级)。
   *
   * @return 当前 placement 映射(空表合法返回空 map)
   */
  Map<String, String> loadAll();
}
