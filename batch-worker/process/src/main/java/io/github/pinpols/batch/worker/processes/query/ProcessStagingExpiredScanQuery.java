package io.github.pinpols.batch.worker.processes.query;

import java.time.Instant;

/** 租户内过期 PROCESS staging 代际的有序续扫条件。 */
public record ProcessStagingExpiredScanQuery(
    String tenantId, int retentionHours, int batchSize, Instant createdAt, String generationId) {}
