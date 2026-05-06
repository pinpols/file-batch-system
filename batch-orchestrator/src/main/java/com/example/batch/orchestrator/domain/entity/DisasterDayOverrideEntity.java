package com.example.batch.orchestrator.domain.entity;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Builder;

/**
 * ADR-023 §决策 §灾难日热切换 — 突发停业 SKIP / DEFER 独立审计行。
 *
 * <p>{@code action}：SKIP / DEFER_TO_NEXT_BIZDAY。
 */
@Builder(toBuilder = true)
public record DisasterDayOverrideEntity(
    Long id,
    String tenantId,
    String calendarCode,
    LocalDate bizDate,
    String action,
    String reason,
    String approvedBy,
    Instant approvedAt,
    Instant effectiveAt,
    Instant ttlUntil,
    Instant createdAt,
    Instant updatedAt) {

  public static final String ACTION_SKIP = "SKIP";
  public static final String ACTION_DEFER_TO_NEXT_BIZDAY = "DEFER_TO_NEXT_BIZDAY";
}
