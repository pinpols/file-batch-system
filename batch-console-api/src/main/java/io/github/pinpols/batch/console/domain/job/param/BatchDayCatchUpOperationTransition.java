package io.github.pinpols.batch.console.domain.job.param;

/** 带原状态和版本的逐批对账状态更新。 */
public record BatchDayCatchUpOperationTransition(
    String tenantId,
    String requestKeyHash,
    long expectedRevision,
    String expectedStatus,
    String nextStatus,
    String itemsJson) {}
