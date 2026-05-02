package com.example.batch.orchestrator.domain.entity;

import java.time.Instant;

/**
 * {@code batch.quota_runtime_state} 行的不可变快照（MyBatis 通过 {@code resultMap+constructor} 映射）。
 *
 * <p>字段顺序与 V25 / V59 DDL 列顺序一致；不可变 record 设计允许在 service 层用 {@link #withPeakAndTimestamps} / {@link
 * #withRefresh} 函数式更新，避免可变状态引发的并发隐患。
 *
 * <p><b>不要加 Spring Data 注解</b>（{@code @Table @Id @Version @Column}）—— 本表已迁 MyBatis 后由 {@code
 * QuotaRuntimeStateMapper} 接管 CRUD + 乐观锁；保留 SDJ 注解会被框架误扫成 Repository。
 */
public record QuotaRuntimeStateRecord(
    Long id,
    String tenantId,
    String quotaScope,
    String ownerCode,
    String quotaResetPolicy,
    Instant windowStartedAt,
    Instant windowExpiresAt,
    Integer peakBorrowedCount,
    Instant lastResetAt,
    Instant createdAt,
    Instant updatedAt,
    Long version) {
  /** 更新峰值和最后时间戳（由 evaluateAndReserve 调用）。 */
  public QuotaRuntimeStateRecord withPeakAndTimestamps(
      int newPeak, Instant lastResetAt, Instant updatedAt) {
    return new QuotaRuntimeStateRecord(
        id,
        tenantId,
        quotaScope,
        ownerCode,
        quotaResetPolicy,
        windowStartedAt,
        windowExpiresAt,
        newPeak,
        lastResetAt,
        createdAt,
        updatedAt,
        version);
  }

  /** 完整窗口刷新（由 reconcileExpiredStates / refreshState 调用）。 */
  public QuotaRuntimeStateRecord withRefresh(
      String quotaResetPolicy,
      Instant windowStartedAt,
      Instant windowExpiresAt,
      int peakBorrowedCount,
      Instant lastResetAt) {
    return new QuotaRuntimeStateRecord(
        id,
        tenantId,
        quotaScope,
        ownerCode,
        quotaResetPolicy,
        windowStartedAt,
        windowExpiresAt,
        peakBorrowedCount,
        lastResetAt,
        createdAt,
        Instant.now(),
        version);
  }
}
