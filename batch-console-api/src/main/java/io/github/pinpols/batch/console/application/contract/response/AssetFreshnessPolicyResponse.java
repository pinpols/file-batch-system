package io.github.pinpols.batch.console.application.contract.response;

import io.github.pinpols.batch.console.domain.entity.AssetFreshnessPolicyEntity;
import java.time.Instant;
import java.time.LocalTime;

/** Public asset freshness policy view. */
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

  public static AssetFreshnessPolicyResponse from(AssetFreshnessPolicyEntity entity) {
    return new AssetFreshnessPolicyResponse(
        entity.id(),
        entity.tenantId(),
        entity.assetCode(),
        entity.assetType(),
        entity.expectedByLocalTime(),
        entity.timezone(),
        entity.staleAfterSeconds(),
        entity.lookbackDays(),
        entity.severity(),
        entity.enabled(),
        entity.createdAt(),
        entity.updatedAt());
  }
}
