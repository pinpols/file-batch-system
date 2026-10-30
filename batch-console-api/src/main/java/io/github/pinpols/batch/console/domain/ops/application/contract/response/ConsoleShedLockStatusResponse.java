package io.github.pinpols.batch.console.domain.ops.application.contract.response;

import java.time.Instant;
import java.util.List;

/**
 * 集群诊断 - ShedLock 租约状态。{@code locks} 行包含可为 null 的字段，保留历史响应中的 null 键。
 */
public record ConsoleShedLockStatusResponse(
    Integer totalLocks, Long activeLocks, List<LockEntry> locks) {

  public record LockEntry(String name, Instant lockUntil, Instant lockedAt, String lockedBy) {}
}
