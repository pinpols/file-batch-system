package com.example.batch.console.mapper.param;

import lombok.Builder;

@Builder
public record ArchivePolicyUpsertParam(
    String tenantId,
    String targetTable,
    int retentionDays,
    boolean archiveEnabled,
    boolean cleanupEnabled,
    int batchSize,
    String description,
    String operator) {}
