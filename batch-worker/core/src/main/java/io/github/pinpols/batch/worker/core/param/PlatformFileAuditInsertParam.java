package io.github.pinpols.batch.worker.core.param;

import lombok.Builder;

/** 文件审计写入参数；保留可空操作者、证据与 JSON 明细。 */
@Builder(toBuilder = true)
public record PlatformFileAuditInsertParam(
    String tenantId,
    Long fileId,
    String operationType,
    String operationResult,
    String operatorType,
    String operatorId,
    String traceId,
    String evidenceRef,
    String detailSummaryJson) {}
