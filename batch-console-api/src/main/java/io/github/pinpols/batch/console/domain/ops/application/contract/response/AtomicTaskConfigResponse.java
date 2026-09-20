package io.github.pinpols.batch.console.domain.ops.application.contract.response;

import io.github.pinpols.batch.console.domain.ops.entity.AtomicTaskConfigEntity;
import java.time.Instant;

/** Public console view of a saved atomic task configuration. */
public record AtomicTaskConfigResponse(
    Long id,
    String tenantId,
    String taskType,
    String name,
    String parameters,
    String createdBy,
    Instant createdAt,
    Instant updatedAt) {

  public static AtomicTaskConfigResponse from(AtomicTaskConfigEntity entity) {
    return new AtomicTaskConfigResponse(
        entity.getId(),
        entity.getTenantId(),
        entity.getTaskType(),
        entity.getName(),
        entity.getParameters(),
        entity.getCreatedBy(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
