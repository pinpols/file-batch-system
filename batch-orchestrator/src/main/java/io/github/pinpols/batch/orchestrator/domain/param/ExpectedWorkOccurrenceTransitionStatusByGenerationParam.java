package io.github.pinpols.batch.orchestrator.domain.param;

import java.time.LocalDate;
import lombok.Builder;

/** 绑定一次transitionStatus操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record ExpectedWorkOccurrenceTransitionStatusByGenerationParam(
    String tenantId,
    String calendarCode,
    Long workflowDefinitionId,
    String sourceType,
    String sourceKey,
    LocalDate bizDate,
    Integer generation,
    String expectedStatus,
    String newStatus,
    String triggerRequestId) {}
