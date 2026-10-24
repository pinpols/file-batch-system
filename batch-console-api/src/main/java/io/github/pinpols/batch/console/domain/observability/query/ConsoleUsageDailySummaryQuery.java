package io.github.pinpols.batch.console.domain.observability.query;

import lombok.Builder;

import java.time.LocalDate;
/** 绑定一次querySummary操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record ConsoleUsageDailySummaryQuery(
    String tenantId,
    LocalDate from,
    LocalDate to,
    String metricCode,
    String pageCode) {}
