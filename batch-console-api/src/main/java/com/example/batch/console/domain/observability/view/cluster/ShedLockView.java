package com.example.batch.console.domain.observability.view.cluster;

import java.time.Instant;

/**
 * batch.shedlock 行的 console 投影。注意:shedlock 表是平台级(无 tenant_id)。
 *
 * <p>保留手写 {@code getXxx()} accessor — 调用方 {@code ConsoleClusterDiagnosticService} 用 bean 风格读字段,
 * 同时配合 record component 自带的 {@code name() / lockUntil() / ...} 双向可用。
 */
public record ShedLockView(String name, Instant lockUntil, Instant lockedAt, String lockedBy) {

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
