package com.example.batch.console.domain.query;

import com.example.batch.common.model.PageRequest;
import java.time.Instant;

public record ConsoleAiAuditLogQuery(
    String tenantId,
    String sessionId,
    String operatorId,
    String promptCategory,
    String promptDecision,
    Instant fromTime,
    Instant toTime,
    PageRequest pageRequest) {}
