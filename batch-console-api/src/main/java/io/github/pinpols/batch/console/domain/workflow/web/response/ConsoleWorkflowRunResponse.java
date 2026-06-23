package io.github.pinpols.batch.console.domain.workflow.web.response;

import java.time.Instant;
import java.time.LocalDate;

public record ConsoleWorkflowRunResponse(
    Long id,
    String tenantId,
    Long workflowDefinitionId,
    Long relatedJobInstanceId,
    LocalDate bizDate,
    String runStatus,
    String currentNodeCode,
    String traceId,
    Instant startedAt,
    Instant finishedAt,
    Instant createdAt,
    Instant updatedAt) {}
