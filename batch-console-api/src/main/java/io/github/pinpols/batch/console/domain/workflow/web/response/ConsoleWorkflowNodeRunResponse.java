package io.github.pinpols.batch.console.domain.workflow.web.response;

import java.time.Instant;

public record ConsoleWorkflowNodeRunResponse(
    Long id,
    Long workflowRunId,
    String nodeCode,
    String nodeType,
    Integer runSeq,
    String nodeStatus,
    Integer retryCount,
    String errorCode,
    String errorMessage,
    Instant startedAt,
    Instant finishedAt,
    Long durationMs) {}
