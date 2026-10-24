package io.github.pinpols.batch.console.application.ops.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 只读预览 Console 中下一轮按原始日期执行的补扫。 */
public record ConsoleBatchDayCatchupPreviewResponse(
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
    Instant catchupPausedAt,
    String catchupPausedBy,
    String catchupPauseReason) {}
