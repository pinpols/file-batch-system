package io.github.pinpols.batch.orchestrator.application.service.batchday;

import java.time.LocalDate;
import java.util.List;

/** Read-only preview of the next raw-date cursor scan; it never opens batch days or advances state. */
public record BatchDayCatchupPreview(
    String tenantId,
    String calendarCode,
    LocalDate currentBizDate,
    LocalDate lastScannedBizDate,
    String cursorSource,
    LocalDate nextRawBizDate,
    LocalDate plannedThroughRawBizDate,
    long remainingRawDateCount,
    int maxDatesPerScan,
    List<LocalDate> nextScanRawBizDates,
    boolean historicalGapsBeforeAnchorAreChecked,
    boolean catchupPaused,
    java.time.Instant catchupPausedAt,
    String catchupPausedBy,
    String catchupPauseReason) {}
