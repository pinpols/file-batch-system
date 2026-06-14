package com.example.batch.common.tenant.routing;

import java.util.Map;

/**
 * 读取「租户 → placement key」显式映射(P2 tenant-routing 表驱动 placement 的数据源)。
 *
 * <p>由 {@link DbTablePlacementResolver} 周期性整表加载到内存缓存,故只需 {@link #loadAll()} 全量读;实现应在表 不存在 / 读失败时返回空
 * map(而非抛错),让 resolver 退回 hash 默认,保证路由不因维护表缺失而中断。
 */
public interface TenantPlacementRepository {

  /** 全量加载 tenant_id → placement_key;表缺失或读失败返回空 map(不抛)。 */
  Map<String, String> loadAll();
}
