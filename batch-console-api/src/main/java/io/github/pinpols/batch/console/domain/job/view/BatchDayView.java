package io.github.pinpols.batch.console.domain.job.view;

import java.time.Instant;
import java.time.LocalDate;

/** 批次日列表与窗口统计查询的固定列投影。 */
public record BatchDayView(
    LocalDate bizDate,
    String dayStatus,
    Instant openAt,
    Instant cutoffAt,
    Instant settledAt,
    Instant slaDeadlineAt,
    Integer lateCount,
    Integer catchupCount,
    Long totalJobCount,
    Long successJobCount,
    Long failedJobCount,
    Long inFlightJobCount) {}
