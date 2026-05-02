package com.example.batch.orchestrator.application.scheduler;

import com.example.batch.orchestrator.domain.scheduling.ResourceCheck;
import java.time.Instant;

/**
 * 配额运行时状态服务：管理租户/队列的突发配额（burst quota）窗口，支持三种重置策略。
 *
 * <p><b>重置策略</b>（{@link com.example.batch.orchestrator.domain.scheduling.QuotaResetPolicy}）：
 *
 * <ul>
 *   <li>{@code NONE} / 非运行时管理策略：直接与 baseCap+burstLimit 比较，无窗口状态
 *   <li>{@code CALENDAR_DAY}：自然日窗口，跨日自动重置峰值借用计数
 *   <li>{@code SLIDING_WINDOW}：滑动窗口（小时数由配置决定），窗口过期后重置
 * </ul>
 *
 * <p>实现选择由 {@code batch.quota.runtime-store} 配置控制：
 *
 * <ul>
 *   <li>{@code redis}（默认）→ {@link
 *       com.example.batch.orchestrator.infrastructure.quota.RedisQuotaRuntimeStateService} Lua
 *       原子脚本实现，无 PG 行级锁竞争，承担派发热路径
 *   <li>{@code database} → {@link
 *       com.example.batch.orchestrator.infrastructure.quota.DatabaseQuotaRuntimeStateService}
 *       PG @Version 乐观锁实现，遗留路径，故障降级 / 短期回退使用
 * </ul>
 */
public interface QuotaRuntimeStateService {

  record QuotaRuntimeSnapshot(
      String quotaResetPolicy,
      Integer burstLimit,
      Integer peakBorrowedCount,
      Integer remainingBurst,
      Instant windowStartedAt,
      Instant windowExpiresAt,
      Instant lastResetAt) {}

  record QuotaReservationOwner(String tenantId, String quotaScope, String ownerCode) {}

  record QuotaReservationPolicy(
      String quotaResetPolicy, int baseCap, int burstLimit, int slidingWindowHours) {}

  record QuotaReservationReason(String reasonCode, String reasonMessage) {}

  record QuotaReservationRequest(
      QuotaReservationOwner owner,
      QuotaReservationPolicy policy,
      long currentActiveCount,
      int requestedCount,
      QuotaReservationReason reason) {}

  record QuotaDescribeRequest(
      QuotaReservationOwner owner,
      String quotaResetPolicy,
      int burstLimit,
      int slidingWindowHours) {}

  /**
   * 判断当前活跃数+请求数是否超过 baseCap+burst。需要 burst 时持久化峰值（{@code peakBorrowedCount}）。
   *
   * <p>返回 {@link ResourceCheck#allow()} 或 {@link ResourceCheck#waitForCapacity(String, String)}。
   */
  ResourceCheck evaluateAndReserve(QuotaReservationRequest request);

  /** Console / 监控读路径：返回当前窗口状态快照，不修改持久化数据。 */
  QuotaRuntimeSnapshot describe(QuotaDescribeRequest request);

  /**
   * 周期 reconcile 入口：清扫过期窗口的状态。
   *
   * <p>Redis 实现：no-op（TTL 自动回收）。<br>
   * PG 实现：扫描 {@code window_expires_at <= now} 的行，重置峰值。
   */
  void reconcileExpiredStates(int slidingWindowHours);
}
