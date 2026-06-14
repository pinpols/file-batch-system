package com.example.batch.common.tenant.routing;

import com.example.batch.common.utils.Texts;
import java.util.Map;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;

/**
 * 表驱动 placement(P2 tenant-routing):显式登记的租户用 {@link TenantPlacementRepository} 表里的 placement
 * key,未登记的退回 {@code fallback}(hash 池化)。表命中优先,实现「池化租户零维护、silo/迁片靠表在线改」。
 *
 * <p>映射整表缓存,{@code cacheTtlMs} 内复用避免每次路由查库;过期后下次 resolve 触发整表重载。缓存粒度足够细 (路由是连接级、毫秒级热路径),TTL
 * 量级建议秒~十秒,迁片登记后最迟一个 TTL 生效。
 *
 * <p>降级语义(区分"空表"与"读失败",保数据正确性):
 *
 * <ul>
 *   <li><b>已有缓存 + 重载失败</b> → 保留上次成功的映射(stale),避免 silo 租户在表 outage 期被 hash 误路由。
 *   <li><b>冷启动(从未成功加载)+ 读失败</b> → 退 hash(此时表里本就没有 silo 指派可丢),下次 resolve 重试。
 * </ul>
 *
 * <p>缓存用单个不可变 {@code Snapshot} 的 volatile 引用,跨字段一致(避免 mapping 与 loadedAt 撕裂)。
 */
@Slf4j
public final class DbTablePlacementResolver implements BusinessPlacementResolver {

  /** 不可变缓存快照:单次 volatile 写保证 mapping/时间/已加载标志三者一致。 */
  private record Snapshot(Map<String, String> mapping, long loadedAtMs, boolean everLoaded) {}

  private final TenantPlacementRepository repository;
  private final BusinessPlacementResolver fallback;
  private final long cacheTtlMs;
  private final LongSupplier clockMs;

  private volatile Snapshot snapshot = new Snapshot(Map.of(), 0L, false);

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
    Snapshot current = snapshot;
    long now = clockMs.getAsLong();
    if (current.everLoaded() && now - current.loadedAtMs() < cacheTtlMs) {
      return current.mapping();
    }
    try {
      Map<String, String> fresh = repository.loadAll();
      // 并发下多线程可能同时重载,均幂等;各自单次 volatile 写,无撕裂
      snapshot = new Snapshot(fresh, now, true);
      return fresh;
    } catch (RuntimeException ex) {
      if (current.everLoaded()) {
        // 有缓存:保留 stale(silo 路由仍正确),bump 时间避免每次都打失败的库
        log.warn(
            "placement reload failed, keep stale mapping ({} entries): {}",
            current.mapping().size(),
            ex.getMessage());
        snapshot = new Snapshot(current.mapping(), now, true);
        return current.mapping();
      }
      // 冷启动读失败:表里本无 silo 指派可丢,退 hash;不写 snapshot,下次 resolve 重试
      log.warn(
          "initial placement load failed, fall back to hash until table reachable: {}",
          ex.getMessage());
      return Map.of();
    }
  }
}
