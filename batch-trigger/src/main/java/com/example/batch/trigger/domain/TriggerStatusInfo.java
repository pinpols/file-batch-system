package com.example.batch.trigger.domain;

import java.time.Instant;

public record TriggerStatusInfo(
        String tenantId,
        String jobCode,
        String scheduleType,
        String scheduleExpression,
        String timezone,
        String triggerMode,
        String status,
        Instant previousFireTime,
        Instant nextFireTime) {}
