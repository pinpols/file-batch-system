package io.github.pinpols.batch.console.domain.ops.application.contract.response;

import io.github.pinpols.batch.console.domain.ops.entity.CustomTaskTypeEntity;
import java.time.Instant;

/** Public console view of an SDK-declared custom task type. */
public record CustomTaskTypeResponse(
    Long id,
    String tenantId,
    String taskTypeCode,
    String displayName,
    String descriptor,
    String descriptorVersion,
    String source,
    String declaredByWorkerCode,
    String status,
    Instant firstDeclaredAt,
    Instant lastDeclaredAt) {

  public static CustomTaskTypeResponse from(CustomTaskTypeEntity entity) {
    return new CustomTaskTypeResponse(
        entity.getId(),
        entity.getTenantId(),
        entity.getTaskTypeCode(),
        entity.getDisplayName(),
        entity.getDescriptor(),
        entity.getDescriptorVersion(),
        entity.getSource(),
        entity.getDeclaredByWorkerCode(),
        entity.getStatus(),
        entity.getFirstDeclaredAt(),
        entity.getLastDeclaredAt());
  }
}
