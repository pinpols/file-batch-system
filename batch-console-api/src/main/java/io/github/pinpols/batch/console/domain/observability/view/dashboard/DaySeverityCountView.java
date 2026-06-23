package io.github.pinpols.batch.console.domain.observability.view.dashboard;

import java.time.LocalDate;

/** dashboard 按 day × severity 双维度的计数投影 (alert_event 日趋势)。 */
public record DaySeverityCountView(LocalDate day, String severity, Long count) {}
