package io.github.pinpols.batch.orchestrator.domain.view;

/** 按租户聚合的成功实例归档引用归属异常计数。 */
public record ArchiveIntegrityCandidateSummary(
    String tenantId, Long affectedInstanceCount, Long sampleInstanceId) {}
