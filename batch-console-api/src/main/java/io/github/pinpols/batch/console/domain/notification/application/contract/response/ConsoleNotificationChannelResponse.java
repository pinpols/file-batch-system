package io.github.pinpols.batch.console.domain.notification.application.contract.response;

import lombok.Builder;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.SecretMasking;
import io.github.pinpols.batch.console.support.web.ConsoleResponseFieldReader;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
/**
 * 通知渠道行响应（{@code NotificationChannelMapper.selectByTenant/selectByCode} 投影）。
 *
 * <p><b>wire 红线</b>：该 mapper 显式列出 snake_case 列且不带别名，历史响应键为 snake_case（{@code channel_code}
 * 等）。故每个多词字段显式 {@code @JsonProperty} 回 snake_case，JSON 键一字不差。{@code config_json} 为 jsonb
 * 列，保留对象结构并递归掩码常规凭据键与 webhook URL。
 *
 * <p>MyBatis {@code resultType="map"} 省略 null 列，历史 wire 不含 null 键 → {@code NON_NULL} 保键集对等。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
public record ConsoleNotificationChannelResponse(
    Long id,
    @JsonProperty("tenant_id") String tenantId,
    @JsonProperty("channel_code") String channelCode,
    @JsonProperty("channel_name") String channelName,
    @JsonProperty("channel_type") String channelType,
    @JsonProperty("config_json") Object configJson,
    Boolean enabled,
    @JsonProperty("created_by") String createdBy,
    @JsonProperty("updated_by") String updatedBy,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("updated_at") Instant updatedAt) {

  public static ConsoleNotificationChannelResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    Long id = ConsoleResponseFieldReader.longValue(row, "id");
    String tenantId = ConsoleResponseFieldReader.stringValue(row, "tenant_id");
    String channelCode = ConsoleResponseFieldReader.stringValue(row, "channel_code");
    String channelName = ConsoleResponseFieldReader.stringValue(row, "channel_name");
    String channelType = ConsoleResponseFieldReader.stringValue(row, "channel_type");
    Object configJsonValue = row.get("config_json");
    Object safeConfigJson = safeConfig(configJsonValue);
    Boolean enabled = ConsoleResponseFieldReader.booleanValue(row, "enabled");
    String createdBy = ConsoleResponseFieldReader.stringValue(row, "created_by");
    String updatedBy = ConsoleResponseFieldReader.stringValue(row, "updated_by");
    Instant createdAt = ConsoleResponseFieldReader.instantValue(row, "created_at");
    Instant updatedAt = ConsoleResponseFieldReader.instantValue(row, "updated_at");
    return ConsoleNotificationChannelResponse.builder()
        .id(id)
        .tenantId(tenantId)
        .channelCode(channelCode)
        .channelName(channelName)
        .channelType(channelType)
        .configJson(safeConfigJson)
        .enabled(enabled)
        .createdBy(createdBy)
        .updatedBy(updatedBy)
        .createdAt(createdAt)
        .updatedAt(updatedAt)
        .build();
  }

  private static Object safeConfig(Object value) {
    if (EmptyChecks.isNull(value)) {
      return null;
    }
    try {
      Object parsed = value instanceof String json ? JsonUtils.fromJson(json, Object.class) : value;
      if (parsed instanceof String) {
        return SecretMasking.MASKED_VALUE;
      }
      return SecretMasking.maskSensitiveValue(parsed, Set.of("url"));
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }
}
