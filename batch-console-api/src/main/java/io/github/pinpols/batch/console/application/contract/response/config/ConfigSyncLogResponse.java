package io.github.pinpols.batch.console.application.contract.response.config;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.domain.config.view.ConfigSyncLogRow;
import java.time.Instant;
import lombok.Builder;

/** 单次配置同步审计日志。 */
@Builder
public record ConfigSyncLogResponse(
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
    Object detailJson,
    String operatorId,
    Instant createdAt) {

  public static ConfigSyncLogResponse from(ConfigSyncLogRow row) {
    if (EmptyChecks.isNull(row)) {
      return null;
    }
    return ConfigSyncLogResponse.builder()
        .id(row.id())
        .tenantId(row.tenantId())
        .syncDirection(row.syncDirection())
        .sourceEnv(row.sourceEnv())
        .targetEnv(row.targetEnv())
        .configTypes(row.configTypes())
        .totalItems(row.totalItems())
        .successItems(row.successItems())
        .failedItems(row.failedItems())
        .skippedItems(row.skippedItems())
        .syncStatus(row.syncStatus())
        .detailJson(row.detailJson())
        .operatorId(row.operatorId())
        .createdAt(row.createdAt())
        .build();
  }
}
