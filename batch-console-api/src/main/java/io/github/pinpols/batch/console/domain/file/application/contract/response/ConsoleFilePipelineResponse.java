package io.github.pinpols.batch.console.domain.file.application.contract.response;

import lombok.Builder;

import java.time.Instant;
@Builder
public record ConsoleFilePipelineResponse(
    Long id,
    String tenantId,
    String jobCode,
    String pipelineType,
    Long fileId,
    Long relatedJobInstanceId,
    String currentStage,
    String lastSuccessStage,
    String runStatus,
    String traceId,
    Instant startedAt,
    Instant finishedAt,
    Instant createdAt,
    Instant updatedAt) {}
