package io.github.pinpols.batch.console.application.contract.response;

import io.github.pinpols.batch.common.persistence.entity.BusinessTenantPlacementEntity;
import java.time.Instant;

/** Public tenant-to-shard placement view. */
public record BusinessTenantPlacementResponse(
    String tenantId, String placementKey, Instant updatedAt, String updatedBy) {

  public static BusinessTenantPlacementResponse from(BusinessTenantPlacementEntity entity) {
    return new BusinessTenantPlacementResponse(
        entity.getTenantId(),
        entity.getPlacementKey(),
        entity.getUpdatedAt(),
        entity.getUpdatedBy());
  }
}
