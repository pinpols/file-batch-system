package io.github.pinpols.batch.console.domain.observability.view.dashboard;

import java.util.List;

/** Dashboard 告警严重度及日趋势统计。 */
public record AlertTrendView(
    List<SeverityCountView> bySeverity, List<DaySeverityCountView> dailyTrend) {}
