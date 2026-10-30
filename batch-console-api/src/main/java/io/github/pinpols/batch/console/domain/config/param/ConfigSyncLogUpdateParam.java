package io.github.pinpols.batch.console.domain.config.param;

import lombok.Builder;

/** 同步审计结果参数；SQL按租户与日志身份限定，并保留JSON合并选择。 */
@Builder
public record ConfigSyncLogUpdateParam(
    String tenantId,
    Long id,
    String syncStatus,
    Integer successItems,
    Integer failedItems,
    Integer skippedItems,
    Boolean mergeDetail,
    String detailJson) {}
