package io.github.pinpols.batch.console.domain.observability.application.contract.response;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.domain.observability.mapper.ConsoleUsageDailyMapper.DailyUsageRow;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Builder;

/** 后端派生使用率日明细；不替代操作审计或业务对账。 */
@Builder
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
    LocalDate statDate = row.statDate();
    String tenantId = row.tenantId();
    String source = row.source();
    String metricCode = row.metricCode();
    String pageCode = row.pageCode();
    String appVersion = row.appVersion();
    long eventCount = row.eventCount();
    long successCount = row.successCount();
    long failureCount = row.failureCount();
    OffsetDateTime lastSeenAtForNullCheck = row.lastSeenAt();
    String lastSeenAtText = null;
    if (EmptyChecks.isNotNull(lastSeenAtForNullCheck)) {
      OffsetDateTime lastSeenAtForText = row.lastSeenAt();
      lastSeenAtText = lastSeenAtForText.toString();
    }
    return ConsoleUsageSummaryResponse.builder()
        .statDate(statDate)
        .tenantId(tenantId)
        .source(source)
        .metricCode(metricCode)
        .pageCode(pageCode)
        .appVersion(appVersion)
        .eventCount(eventCount)
        .successCount(successCount)
        .failureCount(failureCount)
        .lastSeenAt(lastSeenAtText)
        .build();
  }
}
