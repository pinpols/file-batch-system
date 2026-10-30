package io.github.pinpols.batch.console.application.ops.response;

import java.time.LocalDate;
import java.util.List;

/** 列出缺少批次日记录的日期；缺失记录本身不能证明漏处理了业务日。 */
public record ConsoleBatchDayHistoricalGapInventoryResponse(
    String tenantId,
    String calendarCode,
    LocalDate fromDate,
    LocalDate throughDate,
    int inspectedDateCount,
    List<LocalDate> missingRecordCandidates,
    String interpretation) {}
