package com.example.batch.console.web.response;

import java.time.Instant;

public record ConsoleConfigChangeLogResponse(
    Long id,
    String tenantId,
    String configType,
    String configKey,
    Integer versionNo,
    String changeAction,
    String changeResult,
    String operatorType,
    String operatorId,
    String traceId,
    String changeSummaryJson,
    Instant createdAt) {}
