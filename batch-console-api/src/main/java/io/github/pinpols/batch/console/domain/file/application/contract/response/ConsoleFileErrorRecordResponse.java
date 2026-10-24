package io.github.pinpols.batch.console.domain.file.application.contract.response;

import lombok.Builder;

import java.time.Instant;
@Builder
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
