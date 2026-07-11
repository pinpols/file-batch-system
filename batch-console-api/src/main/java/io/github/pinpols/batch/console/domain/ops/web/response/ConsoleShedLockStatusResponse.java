package io.github.pinpols.batch.console.domain.ops.web.response;

import static io.github.pinpols.batch.console.domain.ops.web.response.OpsResponseFieldReader.instantValue;
import static io.github.pinpols.batch.console.domain.ops.web.response.OpsResponseFieldReader.integerValue;
import static io.github.pinpols.batch.console.domain.ops.web.response.OpsResponseFieldReader.longValue;
import static io.github.pinpols.batch.console.domain.ops.web.response.OpsResponseFieldReader.mapList;
import static io.github.pinpols.batch.console.domain.ops.web.response.OpsResponseFieldReader.stringValue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 集群诊断 - ShedLock 租约状态。{@code locks} 行由 service 用 LinkedHashMap 显式 put（含可为 null 的 lockUntil 等）→ 不加
 * {@code NON_NULL}，保留历史 wire 的 null 键。
 */
public record ConsoleShedLockStatusResponse(
    Integer totalLocks, Long activeLocks, List<LockEntry> locks) {

  public record LockEntry(String name, Instant lockUntil, Instant lockedAt, String lockedBy) {
    static LockEntry from(Map<String, Object> row) {
      return new LockEntry(
          stringValue(row, "name"),
          instantValue(row, "lockUntil"),
          instantValue(row, "lockedAt"),
          stringValue(row, "lockedBy"));
    }
  }

  public static ConsoleShedLockStatusResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    return new ConsoleShedLockStatusResponse(
        integerValue(row, "totalLocks"),
        longValue(row, "activeLocks"),
        mapList(row.get("locks")).stream().map(LockEntry::from).toList());
  }
}
