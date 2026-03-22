package com.example.batch.console.domain.query;

public record WorkflowRunQuery(
        String tenantId,
        Long workflowDefinitionId,
        Long relatedJobInstanceId,
        String runStatus,
        String currentNodeCode,
        String traceId
) {
}
