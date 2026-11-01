package io.github.pinpols.batch.console.domain.observability.application.contract.response;

import java.time.LocalDate;
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
    String lastSeenAt) {}
