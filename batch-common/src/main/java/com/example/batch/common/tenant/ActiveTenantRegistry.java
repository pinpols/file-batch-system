package com.example.batch.common.tenant;

import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Citus 租户路由清单源 — 供 trigger / console 等模块按租户循环做 FOR UPDATE 路由时使用。
 *
 * <p>orchestrator 已有专用 {@code ActiveTenantProvider}（T1 交付），本类服务其余模块 （batch-trigger /
 * batch-console-api / batch-worker-core），以相同策略实现避免重复依赖 orchestrator 内部类。
 *
 * <p>内置 30 秒内存缓存：调度器低频调用此方法（秒级轮询），同一 JVM 内无需每次打库。 缓存实现使用简单 volatile 字段 + 同步刷新，无需精确锁——调度器低频调用场景下极偶发的
 * 双重查库（多线程同时穿透）完全可接受。
 *
 * <p>写路径（创建 / 停用租户）归 batch-console-api，本组件严格只读。
 *
 * <p>注册:经 {@code BatchTenantRoutingAutoConfiguration} 走 AutoConfiguration.imports, 不用
 * {@code @Component}——batch-common 是跨模块依赖,下游精简 application(如 e2e app)的 component-scan 范围不保证覆盖本包,只有
 * AutoConfiguration 才对所有 Spring Boot 上下文生效。
 */
@RequiredArgsConstructor
public class ActiveTenantRegistry {

  private final JdbcTemplate jdbcTemplate;

  /** 缓存 TTL(毫秒);生产默认 30s,测试 profile 设 0 关闭缓存以保证确定性(避免 30s 窗口内新插租户读不到)。 */
  @Value("${batch.tenant.active-cache-ttl-millis:30000}")
  private long cacheTtlMillis;

  private volatile List<String> cache;
  private volatile long cacheAtNanos;

  /**
   * 返回当前所有状态为 ACTIVE 的租户标识列表（tenant_id 升序）。
   *
   * <p>距上次查库不足 30 秒且缓存非空时直接返回缓存副本，否则重新查库并刷新缓存。
   *
   * @return 不可变的活跃租户 tenant_id 列表
   */
  public List<String> activeTenantIds() {
    long now = System.nanoTime();
    if (cache != null && (now - cacheAtNanos) < TimeUnit.MILLISECONDS.toNanos(cacheTtlMillis)) {
      return cache;
    }
    List<String> fresh =
        jdbcTemplate.queryForList(
            "select tenant_id from batch.tenant where status = 'ACTIVE' order by tenant_id",
            String.class);
    List<String> immutable = List.copyOf(fresh);
    cache = immutable;
    cacheAtNanos = now;
    return immutable;
  }

  /**
   * 强制使缓存失效，下次调用 {@link #activeTenantIds()} 将重新查库。
   *
   * <p>供测试和运维热更新使用。
   */
  public void invalidateCache() {
    cache = null;
    cacheAtNanos = 0L;
  }
}
