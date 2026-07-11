package io.github.pinpols.batch.console.domain.observability.web.response;

import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.bigDecimalValue;
import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.integerValue;
import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.longValue;
import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.mapList;
import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.stringValue;

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
