package io.github.pinpols.batch.trigger.domain.param;

import java.time.Instant;
import lombok.Builder;

/** 绑定一次transitionStatus操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record ScheduledOccurrenceTransitionStatusParam(
    String tenantId,
    Long workflowDefinitionId,
    Instant scheduledFireTime,
    String expectedStatus,
    String newStatus,
    String requestId) {}
