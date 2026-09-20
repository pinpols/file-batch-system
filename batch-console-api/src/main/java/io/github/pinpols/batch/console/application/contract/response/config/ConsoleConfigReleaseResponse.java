package io.github.pinpols.batch.console.application.contract.response.config;

import java.time.Instant;

public record ConsoleConfigReleaseResponse(
    Long id,
    String tenantId,
    String configType,
    String configKey,
    String configName,
    String configStatus,
    Integer versionNo,
    String configSource,
    String activationMode,
    boolean restartRequired,
    String applyConfirmationStatus,
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
