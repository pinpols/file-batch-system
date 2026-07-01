package io.github.pinpols.batch.console.domain.entity;

import java.time.Instant;
import java.time.LocalTime;

/** JOB asset freshness SLA 策略。 */
public record AssetFreshnessPolicyEntity(
    Long id,
    String tenantId,
    String assetCode,
    String assetType,
    LocalTime expectedByLocalTime,
    String timezone,
    Integer staleAfterSeconds,
    Integer lookbackDays,
    String severity,
    Boolean enabled,
    Instant createdAt,
    Instant updatedAt) {}
