package io.github.pinpols.batch.console.domain.notification.param;

public record JobPushDecisionInsertParam(
    String tenantId,
    String eventKey,
    long jobId,
    String operator,
    String decision,
    String eventType,
    String payload) {}
