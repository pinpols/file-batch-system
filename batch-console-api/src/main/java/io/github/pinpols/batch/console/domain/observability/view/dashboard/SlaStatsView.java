package io.github.pinpols.batch.console.domain.observability.view.dashboard;

import java.math.BigDecimal;

/** dashboard SLA 总览:最近 N 天正式终态实例的违约/达标计数；缺失结束时刻者留在 SLA 分母，不归入达标或违约。 */
public record SlaStatsView(
    Long breached, Long onTime, Long totalWithSla, BigDecimal avgDurationSeconds) {}
