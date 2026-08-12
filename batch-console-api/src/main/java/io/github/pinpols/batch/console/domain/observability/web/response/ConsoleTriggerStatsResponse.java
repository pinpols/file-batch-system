package io.github.pinpols.batch.console.domain.observability.web.response;

import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.longValue;
import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.mapList;
import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.stringValue;

import io.github.pinpols.batch.console.domain.observability.view.dashboard.DayCountView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.TriggerStatsView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.TypeCountView;
import java.util.List;
import java.util.Map;

/** dashboard trigger-stats 响应：按触发类型计数 + 按日趋势（均为固定字段行）。 */
public record ConsoleTriggerStatsResponse(
    List<TypeCountEntry> byTriggerType, List<DayCountEntry> dailyTrend) {

  public record TypeCountEntry(String type, Long count) {
    static TypeCountEntry from(Map<String, Object> row) {
      return new TypeCountEntry(stringValue(row, "type"), longValue(row, "count"));
    }

    static TypeCountEntry from(TypeCountView row) {
      return new TypeCountEntry(
          row.type() == null ? "UNKNOWN" : row.type(), row.count() == null ? 0L : row.count());
    }
  }

  public record DayCountEntry(String day, Long count) {
    static DayCountEntry from(Map<String, Object> row) {
      return new DayCountEntry(stringValue(row, "day"), longValue(row, "count"));
    }

    static DayCountEntry from(DayCountView row) {
      return new DayCountEntry(
          row.day() == null ? "UNKNOWN" : row.day().toString(),
          row.count() == null ? 0L : row.count());
    }
  }

  public static ConsoleTriggerStatsResponse from(TriggerStatsView view) {
    if (view == null) {
      return null;
    }
    return new ConsoleTriggerStatsResponse(
        view.byTriggerType().stream().map(TypeCountEntry::from).toList(),
        view.dailyTrend().stream().map(DayCountEntry::from).toList());
  }

  public static ConsoleTriggerStatsResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    return new ConsoleTriggerStatsResponse(
        mapList(row, "byTriggerType").stream().map(TypeCountEntry::from).toList(),
        mapList(row, "dailyTrend").stream().map(DayCountEntry::from).toList());
  }
}
