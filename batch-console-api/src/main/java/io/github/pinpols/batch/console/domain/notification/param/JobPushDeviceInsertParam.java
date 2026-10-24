package io.github.pinpols.batch.console.domain.notification.param;

public record JobPushDeviceInsertParam(
    String tenantId,
    String eventKey,
    long jobId,
    String operator,
    Long subscriptionId,
    String fingerprint,
    String eventType,
    String payload) {}
