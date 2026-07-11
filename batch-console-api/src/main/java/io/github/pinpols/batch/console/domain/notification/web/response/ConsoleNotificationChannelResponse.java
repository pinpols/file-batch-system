package io.github.pinpols.batch.console.domain.notification.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;

/**
 * 通知渠道行响应（{@code NotificationChannelMapper.selectByTenant/selectByCode} 投影）。
 *
 * <p><b>wire 红线</b>：该 mapper 显式列出 snake_case 列且不带别名，历史响应键为 snake_case（{@code channel_code}
 * 等）。故每个多词字段显式 {@code @JsonProperty} 回 snake_case，JSON 键一字不差。{@code config_json} 为 jsonb
 * 列，按上游运行时原样透传（不改类型/结构），用 {@code Object} 字段承载半结构化配置。
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
        NotificationResponseFieldReader.longValue(row, "id"),
        NotificationResponseFieldReader.stringValue(row, "tenant_id"),
        NotificationResponseFieldReader.stringValue(row, "channel_code"),
        NotificationResponseFieldReader.stringValue(row, "channel_name"),
        NotificationResponseFieldReader.stringValue(row, "channel_type"),
        NotificationResponseFieldReader.value(row, "config_json"),
        NotificationResponseFieldReader.booleanValue(row, "enabled"),
        NotificationResponseFieldReader.stringValue(row, "created_by"),
        NotificationResponseFieldReader.stringValue(row, "updated_by"),
        NotificationResponseFieldReader.instantValue(row, "created_at"),
        NotificationResponseFieldReader.instantValue(row, "updated_at"));
  }
}
