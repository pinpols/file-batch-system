package io.github.pinpols.batch.orchestrator.domain.param;

import lombok.Builder;

/** 文件状态 CAS 参数；当前状态、目标状态与 JSON 合并语义由原 SQL 决定。 */
@Builder
public record FileGovernanceStatusUpdateParam(
    String tenantId, Long fileId, String currentStatus, String nextStatus, String metadataJson) {}
