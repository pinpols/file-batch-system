package io.github.pinpols.batch.orchestrator.domain.param;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Builder;

/** 绑定一次advanceOpenCursor操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record BatchDayInstanceAdvanceOpenCursorParam(
    String tenantId,
    String calendarCode,
    LocalDate expectedBizDate,
    LocalDate nextBizDate,
    Instant scannedAt) {}
