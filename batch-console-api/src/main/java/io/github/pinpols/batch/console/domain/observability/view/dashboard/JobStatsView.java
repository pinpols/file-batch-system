package io.github.pinpols.batch.console.domain.observability.view.dashboard;

import java.util.List;
import java.util.Map;

/** Dashboard 作业实例统计；状态名是动态维度，趋势行保持强类型。 */
public record JobStatsView(
    Map<String, Long> byStatus, Long total, List<DayStatusCountView> dailyTrend) {}
