package io.github.pinpols.batch.console.application.contract.response;

import io.github.pinpols.batch.console.domain.entity.AssetFreshnessPolicyEntity;
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

  public static AssetFreshnessPolicyResponse from(AssetFreshnessPolicyEntity entity) {
    return AssetFreshnessPolicyResponse.builder()
        .id(entity.id())
        .tenantId(entity.tenantId())
        .assetCode(entity.assetCode())
        .assetType(entity.assetType())
        .expectedByLocalTime(entity.expectedByLocalTime())
        .timezone(entity.timezone())
        .staleAfterSeconds(entity.staleAfterSeconds())
        .lookbackDays(entity.lookbackDays())
        .severity(entity.severity())
        .enabled(entity.enabled())
        .createdAt(entity.createdAt())
        .updatedAt(entity.updatedAt())
        .build();
  }
}
