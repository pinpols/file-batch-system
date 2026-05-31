package com.example.batch.common.rls;

import com.example.batch.common.utils.Texts;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * RLS(Row-Level Security)租户上下文 — ThreadLocal 持有当前线程的 `tenant_id`,供 {@link
 * RlsTenantSessionSupport#applyIfPresent} 在事务起点写到 PostgreSQL session 变量 `app.tenant_id`,触发 biz.*
 * 表上的 RLS policy 强制过滤。
 *
 * <p>典型使用(worker 写入入口):
 *
 * <pre>{@code
 * RlsTenantContextHolder.runWithTenant(jobInstance.getTenantId(), () -> {
 *   loadStep.execute(ctx);  // 内部所有 INSERT/UPDATE/SELECT 走 biz DS 时被 RLS 过滤
 * });
 * }</pre>
 *
 * <p>设计:ThreadLocal 是 worker 线程 / Kafka consumer 线程模型下最简方案。如未来切异步反应式 (Reactor / Coroutine)需改 {@code
 * Context.put}。
 *
 * <p>线程清理:`runWithTenant` 用 try/finally 清掉,正常路径无泄漏。AsyncExecutor / TaskDecorator 透传 需要业务侧显式
 * propagate(同 SLF4J MDC 模式)。
 *
 * <p>详见 ADR 多租隔离 plan: {@code docs/plans/multi-tenant-isolation-plan-2026-05-31.md} §Phase A
 */
@Slf4j
public final class RlsTenantContextHolder {

  private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

  private RlsTenantContextHolder() {}

  /** 取当前 ThreadLocal 内的 tenant_id;未设返 null。 */
  public static String get() {
    return HOLDER.get();
  }

  /** 强制设值(慎用)— 推荐 {@link #runWithTenant} 自动清理。 */
  public static void set(String tenantId) {
    if (Texts.hasText(tenantId)) {
      HOLDER.set(tenantId);
    } else {
      HOLDER.remove();
    }
  }

  /** 清空(线程返还池前显式清,防泄漏)。 */
  public static void clear() {
    HOLDER.remove();
  }

  /** 推荐用法:绑定 tenant_id 跑一段代码,完成后自动清。 */
  public static <T> T runWithTenant(String tenantId, Supplier<T> action) {
    String prev = HOLDER.get();
    set(tenantId);
    try {
      return action.get();
    } finally {
      if (prev == null) {
        HOLDER.remove();
      } else {
        HOLDER.set(prev);
      }
    }
  }

  /** void 重载。 */
  public static void runWithTenant(String tenantId, Runnable action) {
    runWithTenant(
        tenantId,
        () -> {
          action.run();
          return null;
        });
  }
}
