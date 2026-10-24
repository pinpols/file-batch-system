package io.github.pinpols.batch.console.domain.notification.application.contract.response;

import io.github.pinpols.batch.common.utils.SecretMasking;
import io.github.pinpols.batch.console.domain.notification.entity.WebhookDeliveryLogEntity;
import java.time.Instant;
import lombok.Builder;

/** Public webhook delivery log view. */
@Builder
public record WebhookDeliveryLogResponse(
    Long id,
    String tenantId,
    Long subscriptionId,
    String eventType,
    String payloadJson,
    Integer httpStatus,
    String responseBody,
    String deliveryStatus,
    Integer attempt,
    Instant nextRetryAt,
    Instant createdAt) {

  public static WebhookDeliveryLogResponse from(WebhookDeliveryLogEntity entity) {
    Long id = entity.getId();
    String tenantId = entity.getTenantId();
    Long subscriptionId = entity.getSubscriptionId();
    String eventType = entity.getEventType();
    String payloadJson = entity.getPayloadJson();
    String safePayloadJson = SecretMasking.maskSensitiveJson(payloadJson);
    Integer httpStatus = entity.getHttpStatus();
    String responseBody = entity.getResponseBody();
    String deliveryStatus = entity.getDeliveryStatus();
    Integer attempt = entity.getAttempt();
    Instant nextRetryAt = entity.getNextRetryAt();
    Instant createdAt = entity.getCreatedAt();
    return WebhookDeliveryLogResponse.builder()
        .id(id)
        .tenantId(tenantId)
        .subscriptionId(subscriptionId)
        .eventType(eventType)
        .payloadJson(safePayloadJson)
        .httpStatus(httpStatus)
        .responseBody(responseBody)
        .deliveryStatus(deliveryStatus)
        .attempt(attempt)
        .nextRetryAt(nextRetryAt)
        .createdAt(createdAt)
        .build();
  }
}
