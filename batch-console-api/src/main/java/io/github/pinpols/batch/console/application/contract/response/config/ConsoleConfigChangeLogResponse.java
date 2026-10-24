package io.github.pinpols.batch.console.application.contract.response.config;

import lombok.Builder;

import java.time.Instant;
@Builder
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
