package io.github.pinpols.batch.console.web.response.config;

import static io.github.pinpols.batch.console.web.response.config.ResponseFieldReader.instantValue;
import static io.github.pinpols.batch.console.web.response.config.ResponseFieldReader.integerValue;
import static io.github.pinpols.batch.console.web.response.config.ResponseFieldReader.longValue;
import static io.github.pinpols.batch.console.web.response.config.ResponseFieldReader.stringValue;
import static io.github.pinpols.batch.console.web.response.config.ResponseFieldReader.value;

import java.time.Instant;
import java.util.Map;

/** 单次配置同步审计日志。 */
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
    return new ConfigSyncLogResponse(
        longValue(row, "id"),
        stringValue(row, "tenantId"),
        stringValue(row, "syncDirection"),
        stringValue(row, "sourceEnv"),
        stringValue(row, "targetEnv"),
        stringValue(row, "configTypes"),
        integerValue(row, "totalItems"),
        integerValue(row, "successItems"),
        integerValue(row, "failedItems"),
        integerValue(row, "skippedItems"),
        stringValue(row, "syncStatus"),
        value(row, "detailJson"),
        stringValue(row, "operatorId"),
        instantValue(row, "createdAt"));
  }
}
