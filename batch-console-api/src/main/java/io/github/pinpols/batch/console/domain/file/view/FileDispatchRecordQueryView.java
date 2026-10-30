package io.github.pinpols.batch.console.domain.file.view;

import java.time.Instant;

/** 文件分发记录列表的固定列投影。 */
public record FileDispatchRecordQueryView(
    Long id,
    String tenantId,
    Long fileId,
    Long pipelineInstanceId,
    String channelCode,
    String dispatchTarget,
    String dispatchStatus,
    Integer dispatchAttempt,
    String receiptCode,
    String receiptStatus,
    String externalRequestId,
    String errorCode,
    String errorMessage,
    String errorKey,
    Object errorArgs,
    Instant dispatchedAt,
    Instant ackAt,
    Instant createdAt,
    Instant updatedAt) {}
