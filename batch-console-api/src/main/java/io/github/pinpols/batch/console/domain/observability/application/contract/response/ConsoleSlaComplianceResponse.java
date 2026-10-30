package io.github.pinpols.batch.console.domain.observability.application.contract.response;

import io.github.pinpols.batch.console.domain.observability.view.dashboard.SlaComplianceView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.SlaDayView;
import java.math.BigDecimal;
import java.util.List;

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

    static SlaDayEntry from(SlaDayView row) {
      return new SlaDayEntry(
          row.day() == null ? "UNKNOWN" : row.day().toString(),
          row.breached() == null ? 0L : row.breached(),
          row.onTime() == null ? 0L : row.onTime());
    }
  }

  public static ConsoleSlaComplianceResponse from(SlaComplianceView view) {
    if (view == null) {
      return null;
    }
    Long breached = view.breached();
    Long onTime = view.onTime();
    Long totalWithSla = view.totalWithSla();
    BigDecimal avgDurationSeconds = view.avgDurationSeconds();
    List<SlaDayEntry> dailyTrend =
        view.dailyTrend().stream().map(SlaDayEntry::from).toList();
    return new ConsoleSlaComplianceResponse(
        breached, onTime, totalWithSla, avgDurationSeconds, dailyTrend);
  }
}
