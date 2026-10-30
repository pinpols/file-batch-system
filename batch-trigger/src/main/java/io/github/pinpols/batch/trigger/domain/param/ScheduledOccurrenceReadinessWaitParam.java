package io.github.pinpols.batch.trigger.domain.param;

import java.time.Instant;
import lombok.Builder;

/** 绑定一次 readiness 等待窗口写入；身份与原 fire 一致，只补时间窗口字段。 */
@Builder
public record ScheduledOccurrenceReadinessWaitParam(
    String tenantId,
    Long workflowDefinitionId,
    Instant scheduledFireTime,
    Instant deferredSince,
    Instant deadline) {}
