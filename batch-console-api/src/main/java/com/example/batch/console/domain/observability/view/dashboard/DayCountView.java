package com.example.batch.console.domain.observability.view.dashboard;

import java.time.LocalDate;

/** dashboard 按 day 单维度的计数投影 (trigger_request 日趋势等)。 */
public record DayCountView(LocalDate day, Long count) {}
