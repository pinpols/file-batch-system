package io.github.pinpols.batch.orchestrator.application.service.batchday;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 只读预览下一轮按原始日期执行的游标扫描；不会开启批次日或推进状态。 */
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
    Instant catchupPausedAt,
    String catchupPausedBy,
    String catchupPauseReason) {}
