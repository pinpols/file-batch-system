package com.example.batch.worker.dispatchs.domain;

public record DispatchPayload(
        String fileId,
        String fileCode,
        String channelCode,
        String dispatchTarget,
        String externalRequestId,
        String receiptCode,
        Boolean ackRequired,
        Boolean forceRetry,
        java.util.Map<String, Object> metadata
) {
}
