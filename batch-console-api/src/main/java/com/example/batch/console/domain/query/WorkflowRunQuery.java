package com.example.batch.console.domain.query;

import com.example.batch.common.model.PageRequest;

public record WorkflowRunQuery(
        String tenantId,
        Long workflowDefinitionId,
        Long relatedJobInstanceId,
        String runStatus,
        String currentNodeCode,
        String traceId,
        PageRequest pageRequest
) {
}
