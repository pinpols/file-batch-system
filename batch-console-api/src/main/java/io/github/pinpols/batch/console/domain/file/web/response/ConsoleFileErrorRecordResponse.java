package io.github.pinpols.batch.console.domain.file.web.response;

import java.time.Instant;

public record ConsoleFileErrorRecordResponse(
    Long id,
    String tenantId,
    Long fileId,
    Long pipelineInstanceId,
    Long pipelineStepRunId,
    Long recordNo,
    String errorCode,
    String errorMessage,
    String errorStage,
    Boolean skipped,
    String skipAction,
    String rawRecord,
    Instant createdAt) {}
