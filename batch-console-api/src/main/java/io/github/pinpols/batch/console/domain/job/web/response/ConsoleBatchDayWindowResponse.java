package io.github.pinpols.batch.console.domain.job.web.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ConsoleBatchDayWindowResponse(
    LocalDate bizDate,
    String dayStatus,
    Instant cutoffAt,
    Instant slaDeadlineAt,
    Instant currentSystemTime,
    Long timeUntilCutoffSeconds,
    Instant lateArrivalWindowClosesAt,
    List<ConsoleBatchDaySummaryResponse> jobs) {}
