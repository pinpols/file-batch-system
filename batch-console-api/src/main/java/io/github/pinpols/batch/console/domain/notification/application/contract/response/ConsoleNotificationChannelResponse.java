package io.github.pinpols.batch.console.domain.notification.application.contract.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
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
    return new ConsoleNotificationChannelResponse(
        ConsoleResponseFieldReader.longValue(row, "id"),
        ConsoleResponseFieldReader.stringValue(row, "tenant_id"),
        ConsoleResponseFieldReader.stringValue(row, "channel_code"),
        ConsoleResponseFieldReader.stringValue(row, "channel_name"),
        ConsoleResponseFieldReader.stringValue(row, "channel_type"),
        safeConfig(row.get("config_json")),
        ConsoleResponseFieldReader.booleanValue(row, "enabled"),
        ConsoleResponseFieldReader.stringValue(row, "created_by"),
        ConsoleResponseFieldReader.stringValue(row, "updated_by"),
        ConsoleResponseFieldReader.instantValue(row, "created_at"),
        ConsoleResponseFieldReader.instantValue(row, "updated_at"));
  }

  private static Object safeConfig(Object value) {
    if (value == null) {
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
