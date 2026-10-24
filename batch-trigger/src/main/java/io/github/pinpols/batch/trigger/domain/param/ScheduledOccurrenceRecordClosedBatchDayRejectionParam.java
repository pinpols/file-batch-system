package io.github.pinpols.batch.trigger.domain.param;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Builder;

/** 绑定一次recordClosedBatchDayRejection操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record ScheduledOccurrenceRecordClosedBatchDayRejectionParam(
    String tenantId,
    Long workflowDefinitionId,
    String workflowCode,
    int scheduleVersion,
    String calendarCode,
    Instant scheduledFireTime,
    LocalDate bizDate,
    String status,
    String reason) {}
