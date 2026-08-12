package io.github.pinpols.batch.console.domain.observability.web.response;

import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.bigDecimalValue;
import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.integerValue;
import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.longValue;
import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.mapList;
import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.stringValue;

import io.github.pinpols.batch.console.domain.observability.view.dashboard.SlaJobReportView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.SlaReportView;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * dashboard sla-report 响应：按 job 维度的 SLA 报表。
 *
 * <p>{@code avgDurationSeconds / maxDurationSeconds} 可为 null，历史 wire 保留显式 null 键 → 不加 {@code
 * NON_NULL}。
 */
public record ConsoleSlaReportResponse(
    String tenantId, Integer periodDays, List<SlaJobEntry> jobs) {

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
      return new SlaJobEntry(
          stringValue(row, "jobCode"),
          stringValue(row, "jobName"),
          longValue(row, "totalInstances"),
          longValue(row, "successCount"),
          longValue(row, "failedCount"),
          longValue(row, "slaBreached"),
          longValue(row, "slaOnTime"),
          bigDecimalValue(row, "avgDurationSeconds"),
          bigDecimalValue(row, "maxDurationSeconds"),
          longValue(row, "totalPartitions"));
    }

    static SlaJobEntry from(SlaJobReportView row) {
      return new SlaJobEntry(
          row.jobCode(),
          row.jobName(),
          row.totalInstances() == null ? 0L : row.totalInstances(),
          row.successCount() == null ? 0L : row.successCount(),
          row.failedCount() == null ? 0L : row.failedCount(),
          row.slaBreached() == null ? 0L : row.slaBreached(),
          row.slaOnTime() == null ? 0L : row.slaOnTime(),
          row.avgDurationSeconds(),
          row.maxDurationSeconds(),
          row.totalPartitions() == null ? 0L : row.totalPartitions());
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
