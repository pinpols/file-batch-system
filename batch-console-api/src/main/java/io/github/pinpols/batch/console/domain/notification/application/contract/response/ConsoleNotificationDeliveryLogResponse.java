package io.github.pinpols.batch.console.domain.notification.application.contract.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.SecretMasking;
import io.github.pinpols.batch.console.domain.notification.view.NotificationDeliveryLogRow;
import java.time.Instant;
import java.util.Set;
import lombok.Builder;
import org.springframework.web.util.HtmlUtils;

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

  public static ConsoleNotificationDeliveryLogResponse from(NotificationDeliveryLogRow row) {
    if (EmptyChecks.isNull(row)) {
      return null;
    }
    return ConsoleNotificationDeliveryLogResponse.builder()
        .id(row.id())
        .tenantId(row.tenantId())
        .ruleId(row.ruleId())
        .channelCode(row.channelCode())
        .eventType(row.eventType())
        .alertEventId(row.alertEventId())
        .payloadJson(maskJsonValue(row.payloadJson()))
        .deliveryStatus(row.deliveryStatus())
        .errorMessage(safeErrorMessage(row.errorMessage()))
        .errorKey(row.errorKey())
        .errorArgs(maskJsonValue(row.errorArgs()))
        .attempt(row.attempt())
        .createdAt(row.createdAt())
        .build();
  }

  private static Object maskJsonValue(String json) {
    return json == null
        ? null
        : SecretMasking.maskSensitiveValue(JsonUtils.fromJson(json, Object.class), Set.of());
  }

  private static String safeErrorMessage(String storedErrorMessage) {
    if (EmptyChecks.isNull(storedErrorMessage)) {
      return null;
    }
    String decoded = HtmlUtils.htmlUnescape(storedErrorMessage);
    String normalized = ConsoleTextSanitizer.normalize(decoded);
    String masked = SwallowedExceptionLogger.safeMessage(normalized, 1024);
    return ConsoleTextSanitizer.safeDisplay(masked, 1024);
  }
}
