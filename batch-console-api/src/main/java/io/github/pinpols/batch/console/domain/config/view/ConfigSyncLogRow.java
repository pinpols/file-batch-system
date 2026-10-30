package io.github.pinpols.batch.console.domain.config.view;

import java.time.Instant;

/**
 * batch.config_sync_log 行投影（ConfigSyncLogMapper.selectByTenant / selectByIdForUpdate，固定 14 列）。
 *
 * <p>detail_json 以文本形态承载（jsonb 列按 String 读取）；success_items 等在运行中为 null 的列按可空 Integer 承载。
 */
public record ConfigSyncLogRow(
    Long id,
    String tenantId,
    String syncDirection,
    String sourceEnv,
    String targetEnv,
    String configTypes,
    Integer totalItems,
    Integer successItems,
    Integer failedItems,
    Integer skippedItems,
    String syncStatus,
    String detailJson,
    String operatorId,
    Instant createdAt) {}
