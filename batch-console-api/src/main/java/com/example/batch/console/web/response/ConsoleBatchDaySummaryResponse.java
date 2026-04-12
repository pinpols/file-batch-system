package com.example.batch.console.web.response;

public record ConsoleBatchDaySummaryResponse(
    String jobCode,
    Integer totalJobCount,
    Integer successJobCount,
    Integer failedJobCount,
    Integer inFlightJobCount,
    Integer catchupCount) {}
