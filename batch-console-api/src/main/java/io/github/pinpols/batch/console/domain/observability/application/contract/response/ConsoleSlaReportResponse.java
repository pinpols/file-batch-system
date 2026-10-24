package io.github.pinpols.batch.console.domain.observability.application.contract.response;

import static io.github.pinpols.batch.console.domain.observability.application.contract.response.ObservabilityResponseFieldReader.bigDecimalValue;
import static io.github.pinpols.batch.console.domain.observability.application.contract.response.ObservabilityResponseFieldReader.integerValue;
import static io.github.pinpols.batch.console.domain.observability.application.contract.response.ObservabilityResponseFieldReader.longValue;
import static io.github.pinpols.batch.console.domain.observability.application.contract.response.ObservabilityResponseFieldReader.mapList;
import static io.github.pinpols.batch.console.domain.observability.application.contract.response.ObservabilityResponseFieldReader.stringValue;

import io.github.pinpols.batch.console.domain.observability.view.dashboard.SlaJobReportView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.SlaReportView;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.Builder;

/**
 * dashboard sla-report 响应：按 job 维度的 SLA 报表。
 *
 * <p>{@code avgDurationSeconds / maxDurationSeconds} 可为 null，历史 wire 保留显式 null 键 → 不加 {@code
 * NON_NULL}。
 */
public record ConsoleSlaReportResponse(
    String tenantId, Integer periodDays, List<SlaJobEntry> jobs) {

  @Builder
  public record SlaJobEntry(
      String jobCode,
      String jobName,
      Long totalInstances,
      Long successCount,
      Long failedCount,
      Long slaBreached,
      Long slaOnTime,
      BigDecimal avgDurationSeconds,
      BigDecimal maxDurationSeconds,
      Long totalPartitions) {
    static SlaJobEntry from(Map<String, Object> row) {
      return SlaJobEntry.builder()
        .jobCode(stringValue(row, "jobCode"))
        .jobName(stringValue(row, "jobName"))
        .totalInstances(longValue(row, "totalInstances"))
        .successCount(longValue(row, "successCount"))
        .failedCount(longValue(row, "failedCount"))
        .slaBreached(longValue(row, "slaBreached"))
        .slaOnTime(longValue(row, "slaOnTime"))
        .avgDurationSeconds(bigDecimalValue(row, "avgDurationSeconds"))
        .maxDurationSeconds(bigDecimalValue(row, "maxDurationSeconds"))
        .totalPartitions(longValue(row, "totalPartitions"))
        .build();
    }

    static SlaJobEntry from(SlaJobReportView row) {
      return SlaJobEntry.builder()
        .jobCode(row.jobCode())
        .jobName(row.jobName())
        .totalInstances(row.totalInstances() == null ? 0L : row.totalInstances())
        .successCount(row.successCount() == null ? 0L : row.successCount())
        .failedCount(row.failedCount() == null ? 0L : row.failedCount())
        .slaBreached(row.slaBreached() == null ? 0L : row.slaBreached())
        .slaOnTime(row.slaOnTime() == null ? 0L : row.slaOnTime())
        .avgDurationSeconds(row.avgDurationSeconds())
        .maxDurationSeconds(row.maxDurationSeconds())
        .totalPartitions(row.totalPartitions() == null ? 0L : row.totalPartitions())
        .build();
    }
  }

  public static ConsoleSlaReportResponse from(SlaReportView view) {
    if (view == null) {
      return null;
    }
    return new ConsoleSlaReportResponse(
        view.tenantId(),
        view.periodDays(),
        view.jobs().stream().map(SlaJobEntry::from).toList());
  }

  public static ConsoleSlaReportResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    return new ConsoleSlaReportResponse(
        stringValue(row, "tenantId"),
        integerValue(row, "periodDays"),
        mapList(row, "jobs").stream().map(SlaJobEntry::from).toList());
  }
}
