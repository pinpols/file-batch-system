package io.github.pinpols.batch.console.domain.file.application.contract.response;

import java.time.Instant;
import java.time.LocalDate;

public record ConsoleFileRecordResponse(
    Long id,
    String tenantId,
    String bizType,
    String fileName,
    String fileStatus,
    LocalDate bizDate,
    String traceId,
    Instant createdAt,
    Instant updatedAt) {}
