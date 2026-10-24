package io.github.pinpols.batch.console.application.ops.command;

import java.time.LocalDate;
import java.util.Objects;
import java.util.List;

/** 一次治理操作的输入；审计操作者由调用方认证上下文提供。 */
public record ForensicExportCommand(
    String tenantId,
    LocalDate bizDateFrom,
    LocalDate bizDateTo,
    List<String> jobCodes,
    String exportFormat,
    String requestedBy) {

  public ForensicExportCommand {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(bizDateFrom, "bizDateFrom");
    Objects.requireNonNull(bizDateTo, "bizDateTo");
  }
}
