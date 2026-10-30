package io.github.pinpols.batch.trigger.domain.param;

import java.time.Instant;
import lombok.Builder;

/** 绑定一次recordCoverage操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record ScheduledOccurrenceRecordCoverageParam(
    String tenantId,
    Long workflowDefinitionId,
    long configurationRevision,
    int scheduleVersion,
    String calendarCode,
    Instant coverageFrom,
    Instant coverageThrough,
    Instant completedAt) {}
