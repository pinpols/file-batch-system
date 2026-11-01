package io.github.pinpols.batch.console.application.contract.response;

import java.time.Instant;
import java.time.LocalTime;
import lombok.Builder;

/** Public asset freshness policy view. */
@Builder
public record AssetFreshnessPolicyResponse(
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
    Instant updatedAt) {

}
