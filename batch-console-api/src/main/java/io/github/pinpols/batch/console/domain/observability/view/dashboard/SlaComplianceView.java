package io.github.pinpols.batch.console.domain.observability.view.dashboard;

import java.math.BigDecimal;
import java.util.List;

/** Dashboard SLA 达标概览及日趋势。 */
public record SlaComplianceView(
    Long breached,
    Long onTime,
    Long totalWithSla,
    BigDecimal avgDurationSeconds,
    List<SlaDayView> dailyTrend) {}
