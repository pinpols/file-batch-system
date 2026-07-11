package io.github.pinpols.batch.console.domain.observability.web.response;

import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.bigDecimalValue;
import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.longValue;
import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.mapList;
import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.stringValue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * dashboard sla-compliance 响应：违约 / 达标 / 有 SLA 总数 + 平均时长 + 按日趋势。
 *
 * <p>{@code avgDurationSeconds} 可为 null（无数据），历史 wire 保留显式 null 键 → 不加 {@code NON_NULL}。
 */
public record ConsoleSlaComplianceResponse(
    Long breached,
    Long onTime,
    Long totalWithSla,
    BigDecimal avgDurationSeconds,
    List<SlaDayEntry> dailyTrend) {

  public record SlaDayEntry(String day, Long breached, Long onTime) {
    static SlaDayEntry from(Map<String, Object> row) {
      return new SlaDayEntry(
          stringValue(row, "day"), longValue(row, "breached"), longValue(row, "onTime"));
    }
  }

  public static ConsoleSlaComplianceResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    return new ConsoleSlaComplianceResponse(
        longValue(row, "breached"),
        longValue(row, "onTime"),
        longValue(row, "totalWithSla"),
        bigDecimalValue(row, "avgDurationSeconds"),
        mapList(row, "dailyTrend").stream().map(SlaDayEntry::from).toList());
  }
}
