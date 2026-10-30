package io.github.pinpols.batch.orchestrator.domain.view;

/** 租户在指定状态下的 Worker 固定聚合投影。 */
public record TenantWorkerCountView(String tenantId, Long cnt) {}
