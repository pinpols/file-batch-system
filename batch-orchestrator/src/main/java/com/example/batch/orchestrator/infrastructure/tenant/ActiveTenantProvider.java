package com.example.batch.orchestrator.infrastructure.tenant;

import com.example.batch.orchestrator.mapper.TenantRoutingMapper;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Citus 租户路由清单提供者 — 全局扫描型调度器按租户循环时的单一数据源。
 *
 * <p>内置 30 秒内存缓存:调度器低频调用此方法(秒级轮询),同一 JVM 内无需每次打库。 缓存实现使用简单 volatile 字段 + 同步刷新,无需精确锁——调度器低频调用场景下极偶发的
 * 双重查库(多线程同时穿透)完全可接受。
 *
 * <p>写路径(创建/停用租户)归 batch-console-api,本组件严格只读。
 */
@Component
@RequiredArgsConstructor
public class ActiveTenantProvider {

  private static final long CACHE_TTL_NANOS = TimeUnit.SECONDS.toNanos(30);

  private final TenantRoutingMapper tenantRoutingMapper;

  private volatile List<String> cache;
  private volatile long cacheAtNanos;

  /**
   * 返回当前所有状态为 ACTIVE 的租户标识列表(tenant_id 升序)。
   *
   * <p>距上次查库不足 30 秒且缓存非空时直接返回缓存副本,否则重新查库并刷新缓存。
   *
   * @return 不可变的活跃租户 tenant_id 列表
   */
  public List<String> activeTenantIds() {
    long now = System.nanoTime();
    if (cache != null && (now - cacheAtNanos) < CACHE_TTL_NANOS) {
      return cache;
    }
    List<String> fresh = tenantRoutingMapper.selectActiveTenantIds();
    List<String> immutable = List.copyOf(fresh);
    cache = immutable;
    cacheAtNanos = now;
    return immutable;
  }

  /**
   * 强制使缓存失效,下次调用 {@link #activeTenantIds()} 将重新查库。
   *
   * <p>供测试和运维热更新使用。
   */
  public void invalidateCache() {
    cache = null;
    cacheAtNanos = 0L;
  }
}
