package io.github.pinpols.batch.console.domain.observability.application.contract.response;

import io.github.pinpols.batch.console.domain.observability.entity.SystemParameterEntity;
import java.time.Instant;

/** Public system parameter view. */
public record SystemParameterResponse(
    Long id,
    String tenantId,
    String paramKey,
    String paramValue,
    String description,
    String createdBy,
    String updatedBy,
    Instant createdAt,
    Instant updatedAt) {

  public static SystemParameterResponse from(SystemParameterEntity entity) {
    return new SystemParameterResponse(
        entity.getId(),
        entity.getTenantId(),
        entity.getParamKey(),
        entity.getParamValue(),
        entity.getDescription(),
        entity.getCreatedBy(),
        entity.getUpdatedBy(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
