package io.github.pinpols.batch.console.domain.notification.application.contract.response;

import io.github.pinpols.batch.console.domain.notification.entity.WebhookDeliveryLogEntity;
import java.time.Instant;

/** Public webhook delivery log view. */
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
    return new WebhookDeliveryLogResponse(
        entity.getId(),
        entity.getTenantId(),
        entity.getSubscriptionId(),
        entity.getEventType(),
        entity.getPayloadJson(),
        entity.getHttpStatus(),
        entity.getResponseBody(),
        entity.getDeliveryStatus(),
        entity.getAttempt(),
        entity.getNextRetryAt(),
        entity.getCreatedAt());
  }
}
