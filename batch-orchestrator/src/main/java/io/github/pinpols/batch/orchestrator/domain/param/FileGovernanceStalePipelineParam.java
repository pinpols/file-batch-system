package io.github.pinpols.batch.orchestrator.domain.param;

import lombok.Builder;

/** 陈旧 Pipeline 批次更新参数；保持原事务与返回实例身份。 */
@Builder
public record FileGovernanceStalePipelineParam(
    String tenantId, String runningStatus, String failedStatus, Long staleSeconds, Integer limit) {}
