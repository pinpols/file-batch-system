package io.github.pinpols.batch.orchestrator.domain.param;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Builder;

/** 绑定一次insertIfAbsent操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record ExpectedWorkOccurrenceInsertIfAbsentParam(
    String tenantId,
    String calendarCode,
    LocalDate bizDate,
    Long workflowDefinitionId,
    String sourceType,
    String sourceKey,
    Instant expectedAt,
    Instant dueAt) {}
