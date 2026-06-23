package io.github.pinpols.batch.worker.core.support;

/**
 * ADR-046 P2 切片 2.3:批量认领的单项(worker → orchestrator {@code POST /internal/tasks/claim-batch})。 字段与单条
 * {@link TaskExecutionClient#claim} 入参一致。
 */
public record TaskClaimItem(String tenantId, Long taskId, String workerId) {}
