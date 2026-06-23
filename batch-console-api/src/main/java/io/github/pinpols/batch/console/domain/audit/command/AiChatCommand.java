package io.github.pinpols.batch.console.domain.audit.command;

import java.util.Map;

public record AiChatCommand(
    String tenantId,
    String sessionId,
    String requestId,
    String traceId,
    String operatorId,
    String prompt,
    Map<String, Object> context,
    String idempotencyKey) {}
