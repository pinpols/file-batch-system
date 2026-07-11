package io.github.pinpols.batch.console.domain.observability.web.response;

import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.longValue;
import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.mapList;
import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.stringValue;

import java.util.List;
import java.util.Map;

/** dashboard alert-trend 响应：按严重度计数 + 按日趋势（均为固定字段行）。 */
public record ConsoleAlertTrendResponse(
    List<SeverityCountEntry> bySeverity, List<DaySeverityCountEntry> dailyTrend) {

  public record SeverityCountEntry(String severity, Long count) {
    static SeverityCountEntry from(Map<String, Object> row) {
      return new SeverityCountEntry(stringValue(row, "severity"), longValue(row, "count"));
    }
  }

  public record DaySeverityCountEntry(String day, String severity, Long count) {
    static DaySeverityCountEntry from(Map<String, Object> row) {
      return new DaySeverityCountEntry(
          stringValue(row, "day"), stringValue(row, "severity"), longValue(row, "count"));
    }
  }

  public static ConsoleAlertTrendResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    return new ConsoleAlertTrendResponse(
        mapList(row, "bySeverity").stream().map(SeverityCountEntry::from).toList(),
        mapList(row, "dailyTrend").stream().map(DaySeverityCountEntry::from).toList());
  }
}
