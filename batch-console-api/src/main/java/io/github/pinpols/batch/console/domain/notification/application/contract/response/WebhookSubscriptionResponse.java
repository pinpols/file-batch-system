package io.github.pinpols.batch.console.domain.notification.application.contract.response;

import io.github.pinpols.batch.console.domain.notification.entity.WebhookSubscriptionEntity;
import java.time.Instant;
import lombok.Builder;

/** Public webhook view; callback secrets are never returned by the API. */
@Builder
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
    return WebhookSubscriptionResponse.builder()
        .id(entity.getId())
        .tenantId(entity.getTenantId())
        .name(entity.getName())
        .callbackUrl(entity.getCallbackUrl())
        .eventTypes(entity.getEventTypes())
        .enabled(entity.getEnabled())
        .createdBy(entity.getCreatedBy())
        .updatedBy(entity.getUpdatedBy())
        .createdAt(entity.getCreatedAt())
        .updatedAt(entity.getUpdatedAt())
        .build();
  }
}
