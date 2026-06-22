package com.example.batch.worker.core.reportoutbox;

/** MyBatis UPSERT 参数（{@link com.example.batch.worker.core.mapper.WorkerReportOutboxPgMapper}）。 */
public record WorkerReportOutboxUpsertParam(
    String tenantId,
    Long taskId,
    String partitionInvocationId,
    String traceId,
    String payloadJson,
    String publishStatus,
    long nextAttemptAt,
    long createdAt,
    long updatedAt) {}
