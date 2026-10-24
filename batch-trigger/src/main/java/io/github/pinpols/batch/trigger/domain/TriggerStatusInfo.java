package io.github.pinpols.batch.trigger.domain;

import java.time.Instant;
import lombok.Builder;

@Builder
public record TriggerStatusInfo(
    String tenantId,
    String jobCode,
    Long workflowDefinitionId,
    String workflowCode,
    String scheduleType,
    String scheduleExpression,
    String timezone,
    String triggerMode,
    String status,
    Instant previousFireTime,
    Instant nextFireTime) {}
