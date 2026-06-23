package io.github.pinpols.batch.console.domain.workflow.query;

import io.github.pinpols.batch.common.model.PageRequest;

public record WorkflowRunQuery(
    String tenantId,
    Long workflowDefinitionId,
    Long relatedJobInstanceId,
    String runStatus,
    String currentNodeCode,
    String traceId,
    PageRequest pageRequest,
    Long cursorId) {}
