package io.github.pinpols.batch.console.domain.job.view;

/** 批次日作业状态统计查询的固定列投影。 */
public record BatchDayJobSummaryView(
    String jobCode,
    Long totalJobCount,
    Long successJobCount,
    Long failedJobCount,
    Long inFlightJobCount,
    Long catchupCount) {}
