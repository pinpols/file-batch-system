package com.example.batch.console.web.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ConsoleBatchDayResponse(
        LocalDate bizDate,
        String dayStatus,
        Instant openAt,
        Instant cutoffAt,
        Instant settledAt,
        Instant slaDeadlineAt,
        String slaStatus,
        Integer totalJobCount,
        Integer successJobCount,
        Integer failedJobCount,
        Integer inFlightJobCount,
        Integer lateCount,
        Integer catchupCount,
        List<ConsoleBatchDaySummaryResponse> catchupSummary
) {
}
