package io.github.pinpols.batch.console.domain.observability.view.dashboard;

import java.math.BigDecimal;

/** dashboard SLA 总览:最近 N 天 finished 实例的违约/达标计数 + 平均执行时长。 */
public record SlaStatsView(
    Long breached, Long onTime, Long totalWithSla, BigDecimal avgDurationSeconds) {}
