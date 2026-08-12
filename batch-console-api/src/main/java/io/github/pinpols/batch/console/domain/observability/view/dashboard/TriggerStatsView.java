package io.github.pinpols.batch.console.domain.observability.view.dashboard;

import java.util.List;

/** Dashboard 触发统计；固定字段用 typed view，避免在服务层拼装 wire Map。 */
public record TriggerStatsView(List<TypeCountView> byTriggerType, List<DayCountView> dailyTrend) {}
