package io.github.pinpols.batch.console.domain.notification.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;

/**
 * 通知投递日志行响应（{@code NotificationDeliveryLogMapper.selectByTenant} 投影）。
 *
 * <p><b>wire 红线</b>：mapper 显式列出 snake_case 列且不带别名，历史响应键为 snake_case（{@code delivery_status} /
 * {@code error_message} 等）。每个多词字段显式 {@code @JsonProperty} 回 snake_case。 {@code payload_json /
 * error_args} 为 jsonb 列，原样透传为 {@code Object}。{@code rule_id / alert_event_id} 为 BIGINT （{@code
 * alert_event_id} 可空）。
 *
 * <p>MyBatis {@code resultType="map"} 省略 null 列 → {@code NON_NULL} 保键集对等。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsoleNotificationDeliveryLogResponse(
    Long id,
    @JsonProperty("tenant_id") String tenantId,
    @JsonProperty("rule_id") Long ruleId,
    @JsonProperty("channel_code") String channelCode,
    @JsonProperty("event_type") String eventType,
    @JsonProperty("alert_event_id") Long alertEventId,
    @JsonProperty("payload_json") Object payloadJson,
    @JsonProperty("delivery_status") String deliveryStatus,
    @JsonProperty("error_message") String errorMessage,
    @JsonProperty("error_key") String errorKey,
    @JsonProperty("error_args") Object errorArgs,
    Integer attempt,
    @JsonProperty("created_at") Instant createdAt) {

  public static ConsoleNotificationDeliveryLogResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    return new ConsoleNotificationDeliveryLogResponse(
        NotificationResponseFieldReader.longValue(row, "id"),
        NotificationResponseFieldReader.stringValue(row, "tenant_id"),
        NotificationResponseFieldReader.longValue(row, "rule_id"),
        NotificationResponseFieldReader.stringValue(row, "channel_code"),
        NotificationResponseFieldReader.stringValue(row, "event_type"),
        NotificationResponseFieldReader.longValue(row, "alert_event_id"),
        NotificationResponseFieldReader.value(row, "payload_json"),
        NotificationResponseFieldReader.stringValue(row, "delivery_status"),
        NotificationResponseFieldReader.stringValue(row, "error_message"),
        NotificationResponseFieldReader.stringValue(row, "error_key"),
        NotificationResponseFieldReader.value(row, "error_args"),
        NotificationResponseFieldReader.integerValue(row, "attempt"),
        NotificationResponseFieldReader.instantValue(row, "created_at"));
  }
}
