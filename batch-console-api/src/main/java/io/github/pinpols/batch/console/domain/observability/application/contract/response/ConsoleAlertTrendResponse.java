package io.github.pinpols.batch.console.domain.observability.application.contract.response;

import io.github.pinpols.batch.console.domain.observability.view.dashboard.AlertTrendView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.DaySeverityCountView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.SeverityCountView;
import java.util.List;

/** dashboard alert-trend 响应：按严重度计数 + 按日趋势（均为固定字段行）。 */
public record ConsoleAlertTrendResponse(
    List<SeverityCountEntry> bySeverity, List<DaySeverityCountEntry> dailyTrend) {

  public record SeverityCountEntry(String severity, Long count) {

    static SeverityCountEntry from(SeverityCountView row) {
      return new SeverityCountEntry(
          row.severity() == null ? "UNKNOWN" : row.severity(),
          row.count() == null ? 0L : row.count());
    }
  }

  public record DaySeverityCountEntry(String day, String severity, Long count) {

    static DaySeverityCountEntry from(DaySeverityCountView row) {
      return new DaySeverityCountEntry(
          row.day() == null ? "UNKNOWN" : row.day().toString(),
          row.severity() == null ? "UNKNOWN" : row.severity(),
          row.count() == null ? 0L : row.count());
    }
  }

  public static ConsoleAlertTrendResponse from(AlertTrendView view) {
    if (view == null) {
      return null;
    }
    List<SeverityCountEntry> severityEntries =
        view.bySeverity().stream().map(SeverityCountEntry::from).toList();
    List<DaySeverityCountEntry> dailyTrendEntries =
        view.dailyTrend().stream().map(DaySeverityCountEntry::from).toList();
    return new ConsoleAlertTrendResponse(severityEntries, dailyTrendEntries);
  }
}
