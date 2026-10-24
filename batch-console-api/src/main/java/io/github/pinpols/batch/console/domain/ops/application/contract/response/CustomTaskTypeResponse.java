package io.github.pinpols.batch.console.domain.ops.application.contract.response;

import io.github.pinpols.batch.console.domain.ops.entity.CustomTaskTypeEntity;
import java.time.Instant;
import lombok.Builder;

/** Public console view of an SDK-declared custom task type. */
@Builder
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
    return CustomTaskTypeResponse.builder()
        .id(entity.getId())
        .tenantId(entity.getTenantId())
        .taskTypeCode(entity.getTaskTypeCode())
        .displayName(entity.getDisplayName())
        .descriptor(entity.getDescriptor())
        .descriptorVersion(entity.getDescriptorVersion())
        .source(entity.getSource())
        .declaredByWorkerCode(entity.getDeclaredByWorkerCode())
        .status(entity.getStatus())
        .firstDeclaredAt(entity.getFirstDeclaredAt())
        .lastDeclaredAt(entity.getLastDeclaredAt())
        .build();
  }
}
