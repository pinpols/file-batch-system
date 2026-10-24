package io.github.pinpols.batch.console.application.ops.command;

import java.time.LocalDate;
import java.util.Objects;

/** 一次治理操作的输入；审计操作者由调用方认证上下文提供。 */
public record CalendarDependencyActionCommand(
    String tenantId,
    String calendarCode,
    LocalDate bizDate,
    String action,
    String operatorId,
    String reason) {

  public CalendarDependencyActionCommand {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(calendarCode, "calendarCode");
    Objects.requireNonNull(bizDate, "bizDate");
    Objects.requireNonNull(action, "action");
  }
}
