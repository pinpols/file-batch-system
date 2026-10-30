package io.github.pinpols.batch.console.domain.ops.view.cluster;

import java.time.Instant;

/**
 * batch.shedlock 行的 console 投影，active 使用数据库查询时的 UTC 时刻判定。表是平台级（无 tenant_id）。
 *
 * <p>保留手写 {@code getXxx()} accessor，供需要 bean 风格属性读取的调用方使用。
 */
public record ShedLockView(
    String name, Instant lockUntil, Instant lockedAt, String lockedBy, Boolean active) {

  public String getName() {
    return name;
  }

  public Instant getLockUntil() {
    return lockUntil;
  }

  public Instant getLockedAt() {
    return lockedAt;
  }

  public String getLockedBy() {
    return lockedBy;
  }
}
