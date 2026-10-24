package io.github.pinpols.batch.console.application.contract.response;

import io.github.pinpols.batch.console.domain.entity.ArchivePolicyEntity;
import java.time.Instant;
import lombok.Builder;

/** Public archive policy view. */
@Builder
public record ArchivePolicyResponse(
    Long id,
    String tenantId,
    String targetTable,
    Integer retentionDays,
    Boolean archiveEnabled,
    Boolean cleanupEnabled,
    Integer batchSize,
    String description,
    String createdBy,
    String updatedBy,
    Instant createdAt,
    Instant updatedAt) {

  public static ArchivePolicyResponse from(ArchivePolicyEntity entity) {
    return ArchivePolicyResponse.builder()
        .id(entity.getId())
        .tenantId(entity.getTenantId())
        .targetTable(entity.getTargetTable())
        .retentionDays(entity.getRetentionDays())
        .archiveEnabled(entity.getArchiveEnabled())
        .cleanupEnabled(entity.getCleanupEnabled())
        .batchSize(entity.getBatchSize())
        .description(entity.getDescription())
        .createdBy(entity.getCreatedBy())
        .updatedBy(entity.getUpdatedBy())
        .createdAt(entity.getCreatedAt())
        .updatedAt(entity.getUpdatedAt())
        .build();
  }
}
