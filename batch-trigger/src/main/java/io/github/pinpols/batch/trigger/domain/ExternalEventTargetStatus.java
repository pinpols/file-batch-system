package io.github.pinpols.batch.trigger.domain;

import java.time.Instant;

public record ExternalEventTargetStatus(
    Long workflowDefinitionId,
    String workflowCode,
    String requestId,
    String traceId,
    String requestStatus,
    Long workflowRunId,
    String workflowRunStatus,
    Long relatedJobInstanceId,
    String instanceStatus,
    Instant updatedAt) {}
