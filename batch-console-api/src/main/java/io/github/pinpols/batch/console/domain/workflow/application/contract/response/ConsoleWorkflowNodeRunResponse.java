package io.github.pinpols.batch.console.domain.workflow.application.contract.response;

import java.time.Instant;

public record ConsoleWorkflowNodeRunResponse(
    Long id,
    Long workflowRunId,
    Long jobInstanceId,
    String nodeCode,
    String nodeType,
    Integer runSeq,
    String nodeStatus,
    Integer retryCount,
    String errorCode,
    String errorMessage,
    String skipReasonCode,
    String skipReason,
    Instant startedAt,
    Instant finishedAt,
    Long durationMs) {}
