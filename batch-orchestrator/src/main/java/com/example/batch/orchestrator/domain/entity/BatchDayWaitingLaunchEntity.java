package com.example.batch.orchestrator.domain.entity;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Builder;

@Builder
public record BatchDayWaitingLaunchEntity(
    Long id,
    String tenantId,
    String calendarCode,
    String jobCode,
    LocalDate bizDate,
    String requestId,
    String traceId,
    String triggerType,
    String waitReason,
    String launchPayload,
    String waitStatus,
    Instant releasedAt,
    String releasedBy,
    Instant createdAt,
    Instant updatedAt) {}
