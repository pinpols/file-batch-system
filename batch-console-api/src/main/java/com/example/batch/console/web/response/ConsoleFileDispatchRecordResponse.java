package com.example.batch.console.web.response;

import java.time.Instant;

public record ConsoleFileDispatchRecordResponse(
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
        Instant dispatchedAt,
        Instant ackAt,
        Instant createdAt,
        Instant updatedAt
) {
}
