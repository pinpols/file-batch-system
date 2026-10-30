package io.github.pinpols.batch.console.domain.observability.application.contract.response;

import io.github.pinpols.batch.console.domain.observability.view.dashboard.DayCountView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.TriggerStatsView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.TypeCountView;
import java.util.List;

/** dashboard trigger-stats 响应：按触发类型计数 + 按日趋势（均为固定字段行）。 */
public record ConsoleTriggerStatsResponse(
    List<TypeCountEntry> byTriggerType, List<DayCountEntry> dailyTrend) {

  public record TypeCountEntry(String type, Long count) {

    static TypeCountEntry from(TypeCountView row) {
      return new TypeCountEntry(
          row.type() == null ? "UNKNOWN" : row.type(), row.count() == null ? 0L : row.count());
    }
  }

  public record DayCountEntry(String day, Long count) {

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
    List<TypeCountEntry> triggerTypeEntries =
        view.byTriggerType().stream().map(TypeCountEntry::from).toList();
    List<DayCountEntry> dailyTrendEntries =
        view.dailyTrend().stream().map(DayCountEntry::from).toList();
    return new ConsoleTriggerStatsResponse(triggerTypeEntries, dailyTrendEntries);
  }
}
