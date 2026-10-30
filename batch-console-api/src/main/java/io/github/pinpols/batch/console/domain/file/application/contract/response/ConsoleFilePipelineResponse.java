package io.github.pinpols.batch.console.domain.file.application.contract.response;

import java.time.Instant;
import lombok.Builder;

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
