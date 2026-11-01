package io.github.pinpols.batch.console.domain.command;

import lombok.Builder;

@Builder
public record ArchivePolicyUpsertCommand(
    String tenantId,
    String targetTable,
    int retentionDays,
    boolean archiveEnabled,
    boolean cleanupEnabled,
    int batchSize,
    String description,
    String operator) {}
