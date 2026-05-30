package com.example.batch.console.domain.ops.web.response;

import java.time.Instant;

public record ConsoleSecretVersionResponse(
    Long id,
    String tenantId,
    String secretRef,
    String secretName,
    Integer versionNo,
    String secretStatus,
    Boolean currentVersion,
    Instant rotationWindowStartAt,
    Instant rotationWindowEndAt,
    Instant effectiveFromAt,
    Instant effectiveToAt,
    String secretPayloadJson,
    String rotationReason,
    String createdBy,
    String updatedBy,
    Instant createdAt,
    Instant updatedAt) {}
