package io.github.pinpols.batch.console.domain.notification.application.contract.response;

import io.github.pinpols.batch.console.domain.notification.entity.WebhookSubscriptionEntity;
import java.time.Instant;

/** Public webhook view; callback secrets are never returned by the API. */
public record WebhookSubscriptionResponse(
    Long id,
    String tenantId,
    String name,
    String callbackUrl,
    String eventTypes,
    Boolean enabled,
    String createdBy,
    String updatedBy,
    Instant createdAt,
    Instant updatedAt) {

  public static WebhookSubscriptionResponse from(WebhookSubscriptionEntity entity) {
    return new WebhookSubscriptionResponse(
        entity.getId(),
        entity.getTenantId(),
        entity.getName(),
        entity.getCallbackUrl(),
        entity.getEventTypes(),
        entity.getEnabled(),
        entity.getCreatedBy(),
        entity.getUpdatedBy(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
