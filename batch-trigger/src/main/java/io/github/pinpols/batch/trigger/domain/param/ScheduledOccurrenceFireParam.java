package io.github.pinpols.batch.trigger.domain.param;

import java.time.Instant;

/** 一次实际 Quartz 回调，不等同于计划身份或请求创建。 */
public record ScheduledOccurrenceFireParam(
    String tenantId,
    Long workflowDefinitionId,
    Instant scheduledFireTime,
    String fireInstanceId,
    int refireCount,
    Instant actualFireTime,
    String fireKind) {}
