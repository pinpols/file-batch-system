package com.example.batch.console.domain.query;

public record WorkflowNodeRunQuery(
        Long workflowRunId,
        String nodeCode,
        String nodeStatus
) {
}
