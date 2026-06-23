package io.github.pinpols.batch.console.domain.observability.view.dashboard;

import java.time.LocalDate;

/** dashboard SLA 日趋势:每天违约/达标计数。 */
public record SlaDayView(LocalDate day, Long breached, Long onTime) {}
