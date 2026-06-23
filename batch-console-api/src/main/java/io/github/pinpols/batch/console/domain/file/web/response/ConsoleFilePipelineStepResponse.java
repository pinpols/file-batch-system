package io.github.pinpols.batch.console.domain.file.web.response;

import java.time.Instant;

public record ConsoleFilePipelineStepResponse(
    Long id,
    Long pipelineInstanceId,
    String stepCode,
    String stageCode,
    Integer runSeq,
    String stepStatus,
    String inputSummary,
    String outputSummary,
    String errorCode,
    String errorMessage,
    Integer retryCount,
    Long durationMs,
    Instant startedAt,
    Instant finishedAt) {}
