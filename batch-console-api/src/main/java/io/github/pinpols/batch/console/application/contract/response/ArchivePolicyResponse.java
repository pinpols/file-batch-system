package io.github.pinpols.batch.console.application.contract.response;

import io.github.pinpols.batch.console.domain.entity.ArchivePolicyEntity;
import java.time.Instant;

/** Public archive policy view. */
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
    return new ArchivePolicyResponse(
        entity.getId(),
        entity.getTenantId(),
        entity.getTargetTable(),
        entity.getRetentionDays(),
        entity.getArchiveEnabled(),
        entity.getCleanupEnabled(),
        entity.getBatchSize(),
        entity.getDescription(),
        entity.getCreatedBy(),
        entity.getUpdatedBy(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
