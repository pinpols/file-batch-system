package io.github.pinpols.batch.orchestrator.domain.entity;

import java.time.LocalTime;

/** JOB asset freshness SLA 策略。 */
public record AssetFreshnessPolicyRecord(
    Long id,
    String tenantId,
    String assetCode,
    String assetType,
    LocalTime expectedByLocalTime,
    String timezone,
    Integer staleAfterSeconds,
    Integer lookbackDays,
    String severity,
    Boolean enabled) {}
