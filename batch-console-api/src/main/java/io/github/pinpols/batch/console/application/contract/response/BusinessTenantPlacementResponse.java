package io.github.pinpols.batch.console.application.contract.response;

import java.time.Instant;

import io.github.pinpols.batch.common.persistence.entity.BusinessTenantPlacementEntity;

/** 公开的租户与分片映射视图。 */
public record BusinessTenantPlacementResponse(
        String tenantId, String placementKey, Instant updatedAt, String updatedBy) {

    public static BusinessTenantPlacementResponse from(BusinessTenantPlacementEntity entity) {
        return new BusinessTenantPlacementResponse(
                entity.getTenantId(), entity.getPlacementKey(), entity.getUpdatedAt(), entity.getUpdatedBy());
    }
}
