package io.github.pinpols.batch.orchestrator.domain.param;

import lombok.Builder;

/** 文件治理审计写入参数；操作者默认值和 JSON 序列化仍由仓库处理。 */
@Builder
public record FileGovernanceAuditInsertParam(
    String tenantId,
    Long fileId,
    String operationType,
    String operationResult,
    String operatorType,
    String operatorId,
    String traceId,
    String detailSummaryJson) {}
