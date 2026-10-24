package io.github.pinpols.batch.console.domain.notification.application.contract.response;

import lombok.Builder;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.pinpols.batch.common.utils.SecretMasking;
import io.github.pinpols.batch.console.support.web.ConsoleResponseFieldReader;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
/**
 * 通知投递日志行响应（{@code NotificationDeliveryLogMapper.selectByTenant} 投影）。
 *
 * <p><b>wire 红线</b>：mapper 显式列出 snake_case 列且不带别名，历史响应键为 snake_case（{@code delivery_status} /
 * {@code error_message} 等）。每个多词字段显式 {@code @JsonProperty} 回 snake_case。 {@code payload_json /
 * error_args} 为 jsonb 列，响应前遮罩敏感值并保留为 {@code Object}。{@code rule_id / alert_event_id} 为 BIGINT
 * （{@code alert_event_id} 可空）。
 *
 * <p>MyBatis {@code resultType="map"} 省略 null 列 → {@code NON_NULL} 保键集对等。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
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
    Long id = ConsoleResponseFieldReader.longValue(row, "id");
    String tenantId = ConsoleResponseFieldReader.stringValue(row, "tenant_id");
    Long ruleId = ConsoleResponseFieldReader.longValue(row, "rule_id");
    String channelCode = ConsoleResponseFieldReader.stringValue(row, "channel_code");
    String eventType = ConsoleResponseFieldReader.stringValue(row, "event_type");
    Long alertEventId = ConsoleResponseFieldReader.longValue(row, "alert_event_id");
    Object payloadJsonValue = ConsoleResponseFieldReader.value(row, "payload_json");
    Object maskedPayloadJson = SecretMasking.maskSensitiveValue(payloadJsonValue, Set.of());
    String deliveryStatus = ConsoleResponseFieldReader.stringValue(row, "delivery_status");
    String errorMessage = ConsoleResponseFieldReader.stringValue(row, "error_message");
    String errorKey = ConsoleResponseFieldReader.stringValue(row, "error_key");
    Object errorArgsValue = ConsoleResponseFieldReader.value(row, "error_args");
    Object maskedErrorArgs = SecretMasking.maskSensitiveValue(errorArgsValue, Set.of());
    Integer attempt = ConsoleResponseFieldReader.integerValue(row, "attempt");
    Instant createdAt = ConsoleResponseFieldReader.instantValue(row, "created_at");
    return ConsoleNotificationDeliveryLogResponse.builder()
        .id(id)
        .tenantId(tenantId)
        .ruleId(ruleId)
        .channelCode(channelCode)
        .eventType(eventType)
        .alertEventId(alertEventId)
        .payloadJson(maskedPayloadJson)
        .deliveryStatus(deliveryStatus)
        .errorMessage(errorMessage)
        .errorKey(errorKey)
        .errorArgs(maskedErrorArgs)
        .attempt(attempt)
        .createdAt(createdAt)
        .build();
  }
}
