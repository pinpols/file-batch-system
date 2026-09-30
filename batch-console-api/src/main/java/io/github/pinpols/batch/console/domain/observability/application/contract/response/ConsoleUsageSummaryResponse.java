package io.github.pinpols.batch.console.domain.observability.application.contract.response;

import io.github.pinpols.batch.console.domain.observability.mapper.ConsoleUsageDailyMapper.DailyUsageRow;
import java.time.LocalDate;

/** 后端派生使用率日明细；不替代操作审计或业务对账。 */
public record ConsoleUsageSummaryResponse(
    LocalDate statDate,
    String tenantId,
    String source,
    String metricCode,
    String pageCode,
    String appVersion,
    long eventCount,
    long successCount,
    long failureCount,
    String lastSeenAt) {

  public static ConsoleUsageSummaryResponse from(DailyUsageRow row) {
    return new ConsoleUsageSummaryResponse(
        row.statDate(),
        row.tenantId(),
        row.source(),
        row.metricCode(),
        row.pageCode(),
        row.appVersion(),
        row.eventCount(),
        row.successCount(),
        row.failureCount(),
        row.lastSeenAt() == null ? null : row.lastSeenAt().toString());
  }
}
