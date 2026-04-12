package com.example.batch.console.web.response;

import java.time.Instant;

public record ConsoleConfigReleaseResponse(
    Long id,
    String tenantId,
    String configType,
    String configKey,
    String configName,
    String configStatus,
    Integer versionNo,
    String grayScopeJson,
    String configPayloadJson,
    Instant effectiveFromAt,
    Instant effectiveToAt,
    Instant publishedAt,
    Instant rolledBackAt,
    String createdBy,
    String updatedBy,
    Instant createdAt,
    Instant updatedAt) {}
