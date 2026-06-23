package io.github.pinpols.batch.console.domain.job.web.response;

public record ConsoleBatchDaySummaryResponse(
    String jobCode,
    Integer totalJobCount,
    Integer successJobCount,
    Integer failedJobCount,
    Integer inFlightJobCount,
    Integer catchupCount) {}
