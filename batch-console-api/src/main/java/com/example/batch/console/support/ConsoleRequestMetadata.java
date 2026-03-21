package com.example.batch.console.support;

public record ConsoleRequestMetadata(
        String requestId,
        String traceId,
        String tenantId,
        String operatorId,
        String idempotencyKey,
        String clientIp
) {
}
