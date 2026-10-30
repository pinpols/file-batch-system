package io.github.pinpols.batch.orchestrator.domain.query;

import lombok.Builder;

/** 文件活跃 Pipeline 计数参数；三种状态仍由仓库按本查询口径提供。 */
@Builder
public record FileGovernanceActivePipelineQuery(
    String tenantId,
    Long fileId,
    String createdStatus,
    String runningStatus,
    String compensatingStatus) {}
