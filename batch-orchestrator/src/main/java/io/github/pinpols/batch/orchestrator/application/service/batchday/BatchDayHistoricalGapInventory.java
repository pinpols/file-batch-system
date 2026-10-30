package io.github.pinpols.batch.orchestrator.application.service.batchday;

import java.time.LocalDate;
import java.util.List;

/** 有界只读清单：列出没有批次日记录的日期。缺失日期仅是候选项。 */
public record BatchDayHistoricalGapInventory(
    String tenantId,
    String calendarCode,
    LocalDate fromDate,
    LocalDate throughDate,
    int inspectedDateCount,
    List<LocalDate> missingRecordCandidates,
    String interpretation) {}
