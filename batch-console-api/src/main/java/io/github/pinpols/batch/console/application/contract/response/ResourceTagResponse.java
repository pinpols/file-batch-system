package io.github.pinpols.batch.console.application.contract.response;

import io.github.pinpols.batch.console.domain.rbac.entity.ResourceTagEntity;
import java.time.Instant;

/** Public resource tag view. */
public record ResourceTagResponse(
    Long id,
    String tenantId,
    String resourceType,
    String resourceCode,
    String tagKey,
    String tagValue,
    String createdBy,
    Instant createdAt) {

  public static ResourceTagResponse from(ResourceTagEntity entity) {
    return new ResourceTagResponse(
        entity.getId(),
        entity.getTenantId(),
        entity.getResourceType(),
        entity.getResourceCode(),
        entity.getTagKey(),
        entity.getTagValue(),
        entity.getCreatedBy(),
        entity.getCreatedAt());
  }
}
