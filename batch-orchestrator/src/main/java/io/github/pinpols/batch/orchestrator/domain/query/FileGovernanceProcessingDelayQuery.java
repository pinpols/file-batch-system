package io.github.pinpols.batch.orchestrator.domain.query;

import lombok.Builder;

/** 处理延迟采样参数；保留租户、观察窗口、阈值和运行状态。 */
@Builder
public record FileGovernanceProcessingDelayQuery(
    String tenantId,
    Long thresholdSeconds,
    Long maxAgeSeconds,
    Integer limit,
    String runningStatus) {}
