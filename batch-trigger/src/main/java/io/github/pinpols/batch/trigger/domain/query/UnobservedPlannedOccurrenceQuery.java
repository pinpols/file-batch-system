package io.github.pinpols.batch.trigger.domain.query;

import java.time.Instant;

/** 按显式关系键解析 Job 关联的受管单作业 Workflow。此查询只使用 */
public record UnobservedPlannedOccurrenceQuery(
    String tenantId,
    Long workflowDefinitionId,
    int scheduleVersion,
    Instant beforeExclusive,
    int limit) {}
