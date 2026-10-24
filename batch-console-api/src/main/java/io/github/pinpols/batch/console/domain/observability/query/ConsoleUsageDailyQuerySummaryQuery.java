package io.github.pinpols.batch.console.domain.observability.query;

import java.time.LocalDate;
import lombok.Builder;

/** 绑定一次querySummary操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record ConsoleUsageDailyQuerySummaryQuery(
    String tenantId,
    LocalDate from,
    LocalDate to,
    String metricCode,
    String pageCode) {}
