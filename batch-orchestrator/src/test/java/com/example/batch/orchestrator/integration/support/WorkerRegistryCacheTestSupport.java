package com.example.batch.orchestrator.integration.support;

import com.example.batch.orchestrator.infrastructure.scheduler.WorkerRegistryCache;

/**
 * 集成测试夹具工具：把跨用例 {@link WorkerRegistryCache} 状态清理收口到测试基础设施。
 *
 * <p>之前 {@code WorkerRegistryCache.evictTenantWorkerSelectors(tenantId)} 是仅给集成测试用的辅助方法
 * 渗入生产代码（IMPORT / DEFAULT / IT / 空 group 4 个 worker_group 一次性 evict 是测试夹具需求,生产 永远走单 group {@link
 * WorkerRegistryCache#evict(String, String)}）。
 *
 * <p>迁到 test scope 的工具类后,生产代码只保留 {@code evict(tenantId, group)} 单点 API,意图清晰。
 */
public final class WorkerRegistryCacheTestSupport {

  /** 集成测试常用 worker_group 集合,与原 {@code evictTenantWorkerSelectors} 行为一致。 */
  private static final String[] COMMON_GROUPS = {"IMPORT", "DEFAULT", "IT", "_"};

  private WorkerRegistryCacheTestSupport() {}

  /**
   * 按租户驱逐集成夹具常用的 worker_group 键位,降低长套件跨用例 Redis 快照干扰。
   *
   * <p>覆盖 {@code IMPORT} / {@code DEFAULT} / {@code IT} 以及空白 worker_group 对应的 {@code _} 占位键(与
   * {@code WorkerRegistryCache#key} 的空组规范化一致)。
   */
  public static void evictTenantWorkerSelectors(WorkerRegistryCache cache, String tenantId) {
    for (String group : COMMON_GROUPS) {
      cache.evict(tenantId, group);
    }
  }
}
