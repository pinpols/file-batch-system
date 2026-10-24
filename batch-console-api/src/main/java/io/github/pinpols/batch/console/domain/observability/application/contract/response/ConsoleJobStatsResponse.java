package io.github.pinpols.batch.console.domain.observability.application.contract.response;

import static io.github.pinpols.batch.console.domain.observability.application.contract.response.ObservabilityResponseFieldReader.longMap;
import static io.github.pinpols.batch.console.domain.observability.application.contract.response.ObservabilityResponseFieldReader.longValue;
import static io.github.pinpols.batch.console.domain.observability.application.contract.response.ObservabilityResponseFieldReader.mapList;
import static io.github.pinpols.batch.console.domain.observability.application.contract.response.ObservabilityResponseFieldReader.stringValue;

import io.github.pinpols.batch.console.domain.observability.view.dashboard.DayStatusCountView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.JobStatsView;
import java.util.List;
import java.util.Map;

/**
 * dashboard job-stats 响应。
 *
 * <p>{@code byStatus} 的键是 job 实例状态值（RUNNING/SUCCESS/…，含 UNKNOWN 占位），属真动态维度键映射 → 保留 {@code
 * Map<String,Long>}（OpenAPI additionalProperties）。{@code dailyTrend} 为固定字段行 → 具名 record。
 */
public record ConsoleJobStatsResponse(
    Map<String, Long> byStatus, Long total, List<DailyTrendEntry> dailyTrend) {

  /** 单日按状态计数（{@code day} 为 LocalDate 或占位符 UNKNOWN，统一以字符串透传，与历史 wire 一致）。 */
  public record DailyTrendEntry(String day, String status, Long count) {
    static DailyTrendEntry from(Map<String, Object> row) {
      return new DailyTrendEntry(
          stringValue(row, "day"), stringValue(row, "status"), longValue(row, "count"));
    }

    static DailyTrendEntry from(DayStatusCountView row) {
      return new DailyTrendEntry(
          row.day() == null ? "UNKNOWN" : row.day().toString(),
          row.status() == null ? "UNKNOWN" : row.status(),
          row.count() == null ? 0L : row.count());
    }
  }

  public static ConsoleJobStatsResponse from(JobStatsView view) {
    if (view == null) {
      return null;
    }
    Map<String, Long> statusCounts = view.byStatus();
    Long total = view.total();
    List<DailyTrendEntry> dailyTrendEntries =
        view.dailyTrend().stream().map(DailyTrendEntry::from).toList();
    return new ConsoleJobStatsResponse(
        statusCounts, total, dailyTrendEntries);
  }

  public static ConsoleJobStatsResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    Map<String, Long> statusCounts = longMap(row, "byStatus");
    Long total = longValue(row, "total");
    List<Map<String, Object>> dailyTrendRows = mapList(row, "dailyTrend");
    List<DailyTrendEntry> dailyTrendEntries =
        dailyTrendRows.stream().map(DailyTrendEntry::from).toList();
    return new ConsoleJobStatsResponse(
        statusCounts, total, dailyTrendEntries);
  }
}
