package io.github.pinpols.batch.orchestrator.domain.param;

import lombok.Builder;

/** 绑定一次markRetrying操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record JobPartitionMarkRetryingParam(
    String tenantId,
    Long id,
    Integer retryCount,
    String retryingStatus,
    Long expectedVersion) {}
