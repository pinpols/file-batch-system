package com.example.batch.console.domain.audit.command;

import java.time.Instant;

public record AiAuditCommand(
    String tenantId,
    String requestId,
    String traceId,
    String sessionId,
    String operatorId,
    String promptCategory,
    String promptDecision,
    String modelName,
    String promptHash,
    String promptPreview,
    String responseHash,
    String responsePreview,
    String refusalReason,
    Instant createdAt) {}
