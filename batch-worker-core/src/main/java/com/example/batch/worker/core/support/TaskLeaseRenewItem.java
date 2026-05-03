package com.example.batch.worker.core.support;

/**
 * ADR-016: one row in {@link TaskExecutionClient#renewLeasesBatch} — aligns with orchestrator renew
 * body + {@code partitionInvocationId} (ADR-014).
 */
public record TaskLeaseRenewItem(
    String tenantId, Long taskId, String workerId, String partitionInvocationId) {}
