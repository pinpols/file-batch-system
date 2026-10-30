package io.github.pinpols.batch.console.domain.param;

import java.time.LocalTime;
import lombok.Builder;

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
