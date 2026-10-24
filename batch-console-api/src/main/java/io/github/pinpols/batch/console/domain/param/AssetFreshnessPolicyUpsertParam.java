package io.github.pinpols.batch.console.domain.param;

import lombok.Builder;

import java.time.LocalTime;
@Builder
public record AssetFreshnessPolicyUpsertParam(
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
