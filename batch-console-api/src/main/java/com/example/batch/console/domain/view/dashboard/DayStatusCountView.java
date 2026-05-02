package com.example.batch.console.domain.view.dashboard;

import java.time.LocalDate;

/** dashboard 按 day × status 双维度的计数投影 (job_instance 日趋势)。 */
public record DayStatusCountView(LocalDate day, String status, Long count) {}
