package io.github.pinpols.batch.orchestrator.domain.param;

import lombok.Builder;

/** 绑定一次promoteStatus操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record JobPartitionPromoteStatusParam(
    String tenantId,
    Long id,
    String fromStatus,
    String toStatus,
    Long expectedVersion) {}
