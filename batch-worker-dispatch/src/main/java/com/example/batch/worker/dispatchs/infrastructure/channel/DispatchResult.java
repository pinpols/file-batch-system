package com.example.batch.worker.dispatchs.infrastructure.channel;

/**
 * 分发操作结果，包含成功标志、外部请求 ID、回执码及回执挂起状态等信息。
 */
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
