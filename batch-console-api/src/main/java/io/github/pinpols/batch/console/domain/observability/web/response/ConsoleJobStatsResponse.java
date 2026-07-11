package io.github.pinpols.batch.console.domain.observability.web.response;

import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.longMap;
import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.longValue;
import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.mapList;
import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.stringValue;

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
  }

  public static ConsoleJobStatsResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    return new ConsoleJobStatsResponse(
        longMap(row, "byStatus"),
        longValue(row, "total"),
        mapList(row, "dailyTrend").stream().map(DailyTrendEntry::from).toList());
  }
}
