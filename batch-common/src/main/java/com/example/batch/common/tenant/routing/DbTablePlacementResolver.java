package com.example.batch.common.tenant.routing;

import com.example.batch.common.utils.Texts;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 表驱动 placement(P2 tenant-routing):显式登记的租户用 {@link TenantPlacementRepository} 表里的 placement
 * key,未登记的退回 {@code fallback}(hash 池化)。表命中优先,实现「池化租户零维护、silo/迁片靠表在线改」。
 *
 * <p>映射整表缓存,{@code cacheTtlMs} 内复用避免每次路由查库;过期后下次 resolve 触发整表重载。缓存粒度足够细 (路由是连接级、毫秒级热路径),TTL
 * 量级建议秒~十秒,迁片登记后最迟一个 TTL 生效。
 *
 * <p>fail-open:repository 读失败返回空 map(见其约定)→ 全部退回 hash,路由不因维护表缺失而中断。
 */
public final class DbTablePlacementResolver implements BusinessPlacementResolver {

  private final TenantPlacementRepository repository;
  private final BusinessPlacementResolver fallback;
  private final long cacheTtlMs;
  private final LongSupplier clockMs;

  private volatile Map<String, String> cache = Map.of();
  private volatile boolean loaded = false;
  private volatile long loadedAtMs = 0L;

  public DbTablePlacementResolver(
      TenantPlacementRepository repository,
      BusinessPlacementResolver fallback,
      long cacheTtlMs,
      LongSupplier clockMs) {
    if (repository == null || fallback == null) {
      throw new IllegalArgumentException("repository 与 fallback 不能为空");
    }
    this.repository = repository;
    this.fallback = fallback;
    this.cacheTtlMs = Math.max(0L, cacheTtlMs);
    this.clockMs = clockMs == null ? System::currentTimeMillis : clockMs;
  }

  @Override
  public String resolve(String tenantId) {
    if (!Texts.hasText(tenantId)) {
      return fallback.resolve(tenantId);
    }
    String key = currentMapping().get(tenantId);
    return key != null ? key : fallback.resolve(tenantId);
  }

  private Map<String, String> currentMapping() {
    long now = clockMs.getAsLong();
    // 首次必加载;之后按 TTL 过期重载(用 loaded 标志避免 long 哨兵相减溢出)
    if (!loaded || now - loadedAtMs >= cacheTtlMs) {
      // 重载;并发下多次重载无害(幂等读),volatile 写让其他线程尽快看到新映射
      cache = repository.loadAll();
      loadedAtMs = now;
      loaded = true;
    }
    return cache;
  }
}
