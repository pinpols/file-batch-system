package com.example.batch.console.domain.query;

import java.time.Instant;

public record ConsoleAiAuditLogQuery(
        String tenantId,
        String sessionId,
        String operatorId,
        String promptCategory,
        String promptDecision,
        Instant fromTime,
        Instant toTime
) {
}
