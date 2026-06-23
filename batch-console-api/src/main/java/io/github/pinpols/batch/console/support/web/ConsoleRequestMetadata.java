package io.github.pinpols.batch.console.support.web;

public record ConsoleRequestMetadata(
    String requestId,
    String traceId,
    String tenantId,
    String operatorId,
    String idempotencyKey,
    String clientIp) {}
