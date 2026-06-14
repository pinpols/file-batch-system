package com.example.batch.console.domain.param;

import lombok.Builder;

/**
 * biz 租户分片 placement 指派/迁片入参(P2 tenant-routing 表驱动)。
 *
 * <p>{@code tenantId} 是被指派的租户,{@code placementKey} 是目标分片(如 {@code shard-0} / {@code silo-big});
 * {@code operator} 落 updated_by 供审计。这是平台 ROLE_ADMIN 跨租操作,非租户自服务。
 */
@Builder
public record BusinessTenantPlacementUpsertParam(
    String tenantId, String placementKey, String operator) {}
