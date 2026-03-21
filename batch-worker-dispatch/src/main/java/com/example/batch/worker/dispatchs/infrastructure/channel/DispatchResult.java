package com.example.batch.worker.dispatchs.infrastructure.channel;

public record DispatchResult(
        boolean success,
        String externalRequestId,
        String receiptCode,
        boolean acknowledged,
        boolean receiptPending,
        String message,
        String evidenceRef
) {
}
