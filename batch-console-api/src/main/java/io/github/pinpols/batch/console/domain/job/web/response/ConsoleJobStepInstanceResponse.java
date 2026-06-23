package io.github.pinpols.batch.console.domain.job.web.response;

import java.time.Instant;

public record ConsoleJobStepInstanceResponse(
    Long id,
    String tenantId,
    Long jobInstanceId,
    Long jobPartitionId,
    Long jobTaskId,
    String stepCode,
    String stepType,
    String stepStatus,
    Integer retryCount,
    Long relatedFileId,
    String resultSummary,
    String errorCode,
    String errorMessage,
    Instant startedAt,
    Instant finishedAt) {}
