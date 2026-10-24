package io.github.pinpols.batch.console.application.contract.response.config;

import static io.github.pinpols.batch.console.application.contract.response.config.ResponseFieldReader.instantValue;
import static io.github.pinpols.batch.console.application.contract.response.config.ResponseFieldReader.integerValue;
import static io.github.pinpols.batch.console.application.contract.response.config.ResponseFieldReader.longValue;
import static io.github.pinpols.batch.console.application.contract.response.config.ResponseFieldReader.stringValue;
import static io.github.pinpols.batch.console.application.contract.response.config.ResponseFieldReader.value;

import java.time.Instant;
import java.util.Map;
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

  public static ConfigSyncLogResponse from(Map<String, Object> row) {
    return ConfigSyncLogResponse.builder()
        .id(longValue(row, "id"))
        .tenantId(stringValue(row, "tenantId"))
        .syncDirection(stringValue(row, "syncDirection"))
        .sourceEnv(stringValue(row, "sourceEnv"))
        .targetEnv(stringValue(row, "targetEnv"))
        .configTypes(stringValue(row, "configTypes"))
        .totalItems(integerValue(row, "totalItems"))
        .successItems(integerValue(row, "successItems"))
        .failedItems(integerValue(row, "failedItems"))
        .skippedItems(integerValue(row, "skippedItems"))
        .syncStatus(stringValue(row, "syncStatus"))
        .detailJson(value(row, "detailJson"))
        .operatorId(stringValue(row, "operatorId"))
        .createdAt(instantValue(row, "createdAt"))
        .build();
  }
}
